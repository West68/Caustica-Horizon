package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Compiles block and entity LabPBR inputs into canonical pages with explicit semantic mip chains. */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    // Entry.features uses the shared MaterialHeader feature bits (RtMaterialRegistry.FEATURE_*).
    // FEATURE_OVERRIDE_EMISSION here means "override mask baked into surface1.a for this candidate".

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int ENTITY_PBR_MAX = 1024;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;

    private final Map<TextureAtlasSprite, Entry> entries = new IdentityHashMap<>();
    // Logical full-texture/sprite name (entity/zombie/zombie or entity/chest/normal) to canonical page
    // mapping. Unlike block entries, albedo UVs are filled by
    // the material-table registry: full textures use [0,1], atlas sprites append their actual atlas rect.
    private final Map<Identifier, Entry> resourceEntries = new HashMap<>();
    // Animated block sprites with authored PBR: one page tile per image frame, indexed by image frame.
    // The registry repoints the sprite's material headers at the live animation frame every frame.
    private final Map<TextureAtlasSprite, Entry[]> animatedFrames = new IdentityHashMap<>();
    private static final long FRAME_PIXEL_BUDGET = 2L << 20;
    private final List<Page> pages = new ArrayList<>();
    private Entry fallback;
    private boolean loggedFailure;

    private RtBlockMaterials() {
    }

    /** Immutable per-sprite page mapping consumed by the material registry. */
    public record Entry(int features, int pageIndex, int maxLod,
                        float materialU, float materialV, float materialDu, float materialDv,
                        float albedoU, float albedoV, float albedoInvDu, float albedoInvDv,
                        RtMaterialDesc.EmissionSummary emissionSummary,
                        RtMaterialDesc.EmissionSummary overrideEmissionSummary,
                        RtEmissionGrid emissionGrid, RtEmissionGrid overrideEmissionGrid) {
    }

    /** Each RGBA channel page is stored as two BC5 halves: RG and BA (see MaterialPageBc5). */
    private record Page(RtMaterialPageTexture surface0, RtMaterialPageTexture normalAo,
                        RtMaterialPageTexture surface1, RtMaterialPageTexture surface0B,
                        RtMaterialPageTexture normalAoB, RtMaterialPageTexture surface1B,
                        RtMaterialPageTexture heightBox, int index) {
        void destroy() {
            surface0.destroy();
            normalAo.destroy();
            surface1.destroy();
            surface0B.destroy();
            normalAoB.destroy();
            surface1B.destroy();
            heightBox.destroy();
        }
    }

    private static final class Candidate {
        final TextureAtlasSprite sprite;
        final Identifier name;
        final Identifier albedoLocation;
        final int features;
        final Identifier specLocation;
        final Identifier normalLocation;
        final int width;
        final int height;
        int storageWidth, storageHeight;
        int page = -1;
        PackedMaterialTile tile;
        Candidate alias;
        int x;
        int y;
        int frame, frameCount = 1; // image frame of this tile; frameCount on the frame-0 candidate
        RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
        RtMaterialDesc.EmissionSummary overrideEmissionSummary = RtMaterialDesc.EmissionSummary.NONE;
        RtEmissionGrid emissionGrid;
        RtEmissionGrid overrideEmissionGrid;

        Candidate(TextureAtlasSprite sprite, Identifier name, Identifier albedoLocation, int features,
                  Identifier specLocation, Identifier normalLocation, int width, int height) {
            this.sprite = sprite;
            this.name = name;
            this.albedoLocation = albedoLocation;
            this.features = features;
            this.specLocation = specLocation;
            this.normalLocation = normalLocation;
            this.width = width;
            this.height = height;
        }

        boolean blockSprite() {
            return sprite != null;
        }
    }

    private static final class LayoutPage {
        final int size;
        int x;
        int y;
        int rowHeight;
        int occupiedHeight;

        LayoutPage(int size, boolean reserveFallback) {
            this.size = size;
            if (reserveFallback) y = align(1 + 2 * GUTTER, PACK_ALIGNMENT);
            occupiedHeight=y;
        }

        boolean place(Candidate candidate) {
            int cellWidth = align(candidate.storageWidth + 2 * GUTTER, PACK_ALIGNMENT);
            int cellHeight = align(candidate.storageHeight + 2 * GUTTER, PACK_ALIGNMENT);
            if (cellWidth > size || cellHeight > size) return false;
            if (x + cellWidth > size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + cellHeight > size) return false;
            candidate.x = x + GUTTER;
            candidate.y = y + GUTTER;
            occupiedHeight=Math.max(occupiedHeight,y+cellHeight);
            x += cellWidth;
            rowHeight = Math.max(rowHeight, cellHeight);
            return true;
        }
    }

    /** Drop the previous epoch's CPU mappings and GPU pages. Caller owns the idle/reload boundary. */
    public void reset() {
        dev.comfyfluffy.caustica.client.LitematicaCompat.releaseMaterialBindings();
        entries.clear();
        resourceEntries.clear();
        animatedFrames.clear();
        fallback = null;
        for (Page page : pages) page.destroy();
        pages.clear();
    }

    /** Compile, pack, mip, upload, and publish block-atlas plus authored entity material pages. */
    public void prepareAll(RtContext ctx, int materialPageCapacity, RtEmissionSemantics emissionSemantics,
                           RtMaterialOverrides overrides) {
        List<TextureAtlasSprite> sprites = blockSprites();
        List<Candidate> authored = new ArrayList<>();
        int specCount = 0;
        int normalCount = 0;
        int heuristicCount = 0;
        int overrideMaskCount = 0;
        int largest = 1 + 2 * GUTTER;
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            Identifier name = sprite.contents().name();
            Identifier spec = sibling(name, "_s.png");
            Identifier normal = sibling(name, "_n.png");
            int features = 0;
            if (resourceExists(spec)) {
                features |= RtMaterialRegistry.FEATURE_SPEC;
                specCount++;
            }
            if (resourceExists(normal)) {
                features |= RtMaterialRegistry.FEATURE_NORMAL;
                normalCount++;
            }
            // Authored LabPBR owns emission whenever _s exists. Albedo inference is only compiled for
            // sprites proven to occur on an emitting block state.
            if ((features & RtMaterialRegistry.FEATURE_SPEC) == 0 && emissionSemantics.permits(sprite)) {
                features |= RtMaterialRegistry.FEATURE_HEURISTIC_EMISSION;
                heuristicCount++;
            }
            if (overrides.requestsEmissionMask(sprite)) {
                features |= RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION;
                overrideMaskCount++;
            }
            if (features != 0) {
                int width = sprite.contents().width();
                int height = sprite.contents().height();
                largest = Math.max(largest, Math.max(width, height) + 2 * GUTTER);
                authored.add(new Candidate(sprite, name, null, features, spec, normal, width, height));
            }
        }
        // The page used to hold only frame 0 of animated maps, so a saw whose teeth live in its normal/height
        // map (stonecutter) stood still while its flat albedo scrolled. Store every frame, fewest-frame
        // sprites first, within a fixed pixel budget.
        List<Candidate> animated = new ArrayList<>();
        for (Candidate candidate : authored) {
            if ((candidate.features & (RtMaterialRegistry.FEATURE_SPEC | RtMaterialRegistry.FEATURE_NORMAL)) == 0
                    || !candidate.sprite.contents().isAnimated()) continue;
            NativeImage original = ((SpriteContentsAccessor) candidate.sprite.contents()).caustica$originalImage();
            if (original == null || original.getWidth() != candidate.width) continue;
            candidate.frameCount = Math.min(64, original.getHeight() / candidate.height);
            if (candidate.frameCount > 1) animated.add(candidate);
        }
        animated.sort(Comparator.comparingLong(c -> (long) c.frameCount * c.width * c.height));
        long framePixels = 0;
        for (Candidate primary : animated) {
            long pixels = (long) (primary.frameCount - 1) * primary.width * primary.height;
            if (framePixels + pixels > FRAME_PIXEL_BUDGET) { primary.frameCount = 1; continue; }
            framePixels += pixels;
            for (int frame = 1; frame < primary.frameCount; frame++) {
                Candidate copy = new Candidate(primary.sprite, primary.name, null, primary.features,
                        primary.specLocation, primary.normalLocation, primary.width, primary.height);
                copy.frame = frame;
                copy.frameCount = primary.frameCount;
                authored.add(copy);
            }
        }
        Map<Identifier, Integer> resourceFeatures = discoverEntityMaterialResources(sprites, overrides);
        for (Map.Entry<Identifier, Integer> discovered : resourceFeatures.entrySet()) {
            Identifier albedo = discovered.getKey();
            int features = discovered.getValue();
            try (NativeImage image = load(albedo)) {
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
                Identifier name = logicalTextureName(albedo);
                int width = image.getWidth();
                int height = image.getHeight();
                // Hi-res model packs (YSM/2048px+ entity skins) multiplied the material pages to 3.4 GiB and
                // pushed 8 GB cards into paging. Entity PBR detail is stored at most 1024px on its long side;
                // the albedo the shader samples stays the full-resolution entity texture.
                int longSide = Math.max(width, height);
                if (longSide > ENTITY_PBR_MAX) {
                    width = Math.max(1, width * ENTITY_PBR_MAX / longSide);
                    height = Math.max(1, height * ENTITY_PBR_MAX / longSide);
                }
                Identifier spec = siblingTexture(albedo, "_s");
                Identifier normal = siblingTexture(albedo, "_n");
                largest = Math.max(largest, Math.max(width, height) + 2 * GUTTER);
                authored.add(new Candidate(null, name, albedo, features, spec, normal, width, height));
                if ((features & RtMaterialRegistry.FEATURE_SPEC) != 0) specCount++;
                if ((features & RtMaterialRegistry.FEATURE_NORMAL) != 0) normalCount++;
                if ((features & RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION) != 0) overrideMaskCount++;
            } catch (Throwable t) {
                warnOnce("RT entity material albedo load failed for " + albedo, t);
            }
        }

        int pageSize = authored.isEmpty() ? 32 : Math.max(DEFAULT_PAGE_SIZE, nextPowerOfTwo(largest));
        if (pageSize > MAX_PAGE_SIZE) {
            CausticaMod.LOGGER.warn("RT material sprite exceeds canonical page limit ({} > {}); oversized maps use neutral fallback",
                    pageSize, MAX_PAGE_SIZE);
            pageSize = MAX_PAGE_SIZE;
            authored.removeIf(candidate -> candidate.width + 2 * GUTTER > MAX_PAGE_SIZE
                    || candidate.height + 2 * GUTTER > MAX_PAGE_SIZE);
            if (authored.isEmpty()) pageSize = 32;
        }
        authored.sort(Comparator.<Candidate>comparingInt(candidate -> candidate.height).reversed()
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.width).reversed())
                .thenComparing(candidate -> candidate.name.toString()));

        // Decode once, then intern exact uploaded texels before assigning GPU space. Resource packs
        // often repeat identical PBR tiles across model/CTM/entity variants. Keep independent material
        // headers and emission summaries; only their byte-identical texture storage is shared.
        Map<PackedMaterialTile, Candidate> uniqueTiles = new HashMap<>();
        List<Candidate> unique = new ArrayList<>();
        authored.parallelStream().forEach(candidate -> {
            try {
                Decoded decoded=decode(candidate);
                candidate.emissionSummary=decoded.emissionSummary();
                candidate.overrideEmissionSummary=decoded.overrideEmissionSummary();
                candidate.emissionGrid=decoded.emissionGrid(); candidate.overrideEmissionGrid=decoded.overrideEmissionGrid();
                candidate.tile=new PackedMaterialTile(decoded.levels());
            } catch(Throwable t) { warnOnce("RT canonical material decode failed for "+candidate.name,t); }
        });
        for(Candidate candidate:authored) {
            if(candidate.tile==null)continue;
            Candidate same=uniqueTiles.putIfAbsent(candidate.tile,candidate);
            candidate.storageWidth=candidate.tile.width; candidate.storageHeight=candidate.tile.height;
            if(same==null)unique.add(candidate);
            else { candidate.alias=same; candidate.tile=null; }
        }
        uniqueTiles.clear();
        // Power-of-two pages waste entire rows on e.g. 512px tiles plus 16px gutters.
        // Vulkan supports NPOT sampled images. Compare exact allocation sizes, preserving texels,
        // gutters, packing alignment and all semantic LODs; the chosen layout is recomputed once.
        int minimumPage=pageSize, bestPage=pageSize;
        long minimumBytes=Long.MAX_VALUE;
        for(int trial=minimumPage;trial<=MAX_PAGE_SIZE;trial+=128) {
            var packed=pack(unique,trial);
            if(packed.size()>materialPageCapacity)continue;
            long bytes=0;
            for(var page:packed)bytes+=MaterialPageCrop.bytes(trial,MaterialPageCrop.height(trial,page.occupiedHeight,MAX_VALID_LOD));
            if(bytes<minimumBytes){minimumBytes=bytes;bestPage=trial;}
        }
        pageSize=bestPage;
        List<LayoutPage> layouts=pack(unique,pageSize);
        if (layouts.size() > materialPageCapacity) {
            throw new IllegalStateException("RT material pages require " + layouts.size()
                    + " descriptor slots but capacity is " + materialPageCapacity);
        }

        int[] pageHeights=new int[layouts.size()];
        for(int i=0;i<layouts.size();i++)pageHeights[i]=MaterialPageCrop.height(pageSize,layouts.get(i).occupiedHeight,MAX_VALID_LOD);
        int mipCount = Integer.SIZE - Integer.numberOfLeadingZeros(pageSize);
        PagePixels[] pagePixels = new PagePixels[layouts.size()];
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            pagePixels[pageIndex] = new PagePixels(pageSize, pageHeights[pageIndex], mipCount);
        }
        pagePixels[0].writeFallback();
        unique.parallelStream().forEach(candidate -> pagePixels[candidate.page].write(candidate, candidate.tile));
        for(Candidate candidate:authored) {
            if(candidate.alias!=null) {
                candidate.page=candidate.alias.page; candidate.x=candidate.alias.x; candidate.y=candidate.alias.y;
            }
        }
        CausticaMod.LOGGER.info("RT material storage: {} authored tiles, {} exact unique tiles",authored.size(),unique.size());
        for(Candidate candidate:unique) candidate.tile=null;
        final int boxPageSize=pageSize;
        byte[][] heightBoxes=new byte[layouts.size()][];
        for(int i=0;i<layouts.size();i++)heightBoxes[i]=new byte[pageSize*pageHeights[i]];
        unique.parallelStream().filter(c->c.page>=0 && c.page<heightBoxes.length && (c.features & RtMaterialRegistry.FEATURE_NORMAL)!=0)
                .forEach(c->writeHeightBox(heightBoxes[c.page],pagePixels[c.page].normalAo.get(0),boxPageSize,pageHeights[c.page],c));
        Map<PageKey,RtMaterialPageTexture[]> uploaded=new HashMap<>();
        List<RtMaterialPageTexture> heightBoxPages=new ArrayList<>();
        int pageStart=pages.size();
        try {
        for(int pageIndex=0;pageIndex<layouts.size();pageIndex++) {
            PagePixels pixels=pagePixels[pageIndex];
            int height=pageHeights[pageIndex];
            RtMaterialPageTexture heightBox=new RtMaterialPageTexture(ctx,pageSize,height,
                    List.of(heightBoxes[pageIndex]),"material height box "+pageIndex,org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM);
            heightBoxPages.add(heightBox);
            RtMaterialPageTexture[] s0=uploadPage(ctx,pageSize,height,pixels.surface0,uploaded);
            RtMaterialPageTexture[] n=uploadPage(ctx,pageSize,height,pixels.normalAo,uploaded);
            RtMaterialPageTexture[] s1=uploadPage(ctx,pageSize,height,pixels.surface1,uploaded);
            pages.add(new Page(s0[0],n[0],s1[0],s0[1],n[1],s1[1],heightBox,pageIndex));
        }
        } catch(Throwable failure) {
            // No page is exposed to a descriptor yet. Retire every successful allocation, including
            // channels created before a later image in the same bundle failed under VRAM pressure.
            for(var pair:uploaded.values())for(var image:pair)image.destroy();
            for(var image:heightBoxPages)image.destroy();
            pages.subList(pageStart,pages.size()).clear();
            throw failure;
        }
        long allocatedBytes=uploaded.values().stream().flatMap(java.util.Arrays::stream).mapToLong(RtMaterialPageTexture::allocatedBytes).sum()
                +heightBoxPages.stream().mapToLong(RtMaterialPageTexture::allocatedBytes).sum();
        uploaded.clear();
        CausticaMod.LOGGER.info("RT material actual Vulkan allocation: {} MiB",allocatedBytes/(1024.0*1024.0));

        float fallbackUv = GUTTER / (float) pageSize;
        fallback = new Entry(0, 0, 0, fallbackUv, GUTTER / (float)pageHeights[0],
                1.0f / pageSize, 1.0f / pageHeights[0], 0, 0, 1, 1,
                RtMaterialDesc.EmissionSummary.NONE, RtMaterialDesc.EmissionSummary.NONE, null, null);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite != null) entries.put(sprite, fallbackFor(sprite));
        }
        for (Candidate candidate : authored) {
            if (candidate.page < 0) continue;
            int maxLod = maxLodFor(candidate.width, candidate.height);
            Entry entry = new Entry(candidate.features, candidate.page, maxLod,
                    candidate.x / (float) pageSize, candidate.y / (float) pageHeights[candidate.page],
                    candidate.storageWidth / (float) pageSize, candidate.storageHeight / (float) pageHeights[candidate.page],
                    candidate.blockSprite() ? candidate.sprite.getU0() : 0.0f,
                    candidate.blockSprite() ? candidate.sprite.getV0() : 0.0f,
                    candidate.blockSprite() ? inverseExtent(candidate.sprite.getU1() - candidate.sprite.getU0()) : 1.0f,
                    candidate.blockSprite() ? inverseExtent(candidate.sprite.getV1() - candidate.sprite.getV0()) : 1.0f,
                    candidate.emissionSummary, candidate.overrideEmissionSummary,
                    candidate.emissionGrid, candidate.overrideEmissionGrid);
            if (candidate.blockSprite() && candidate.frameCount > 1)
                animatedFrames.computeIfAbsent(candidate.sprite, s -> new Entry[candidate.frameCount])[candidate.frame] = entry;
            if (candidate.blockSprite()) { if (candidate.frame == 0) entries.put(candidate.sprite, entry); }
            else resourceEntries.put(candidate.name, entry);
        }

        long textureBytes=allocatedBytes;
        CausticaMod.LOGGER.info("RT canonical material pages: blockSprites={}, entityResources={}, spec={}, normal={}, heuristicEmission={}, overrideMasks={}, pages={}, width={}, heights={}, validLod<={}, gpuMiB={}",
                sprites.size(), resourceEntries.size(), specCount, normalCount, heuristicCount, overrideMaskCount,
                pages.size(), pageSize, java.util.Arrays.toString(pageHeights), MAX_VALID_LOD,
                String.format(java.util.Locale.ROOT, "%.2f", textureBytes / (1024.0 * 1024.0)));

    }

    private static List<LayoutPage> pack(List<Candidate> unique,int pageSize) {
        List<LayoutPage> layouts=new ArrayList<>();layouts.add(new LayoutPage(pageSize,true));
        for (Candidate candidate : unique) {
            boolean placed = false;
            for (int i = 0; i < layouts.size(); i++) {
                if (layouts.get(i).place(candidate)) {
                    candidate.page = i;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                LayoutPage page = new LayoutPage(pageSize, false);
                if (!page.place(candidate)) continue;
                candidate.page = layouts.size();
                layouts.add(page);
            }
        }
        return layouts;
    }

    private record PageKey(List<byte[]> levels) {
        @Override public int hashCode() { int hash=1;for(byte[] level:levels)hash=31*hash+java.util.Arrays.hashCode(level);return hash; }
        @Override public boolean equals(Object other) {
            if(!(other instanceof PageKey key)||levels.size()!=key.levels.size())return false;
            for(int i=0;i<levels.size();i++)if(!java.util.Arrays.equals(levels.get(i),key.levels.get(i)))return false;
            return true;
        }
    }
    private static RtMaterialPageTexture[] uploadPage(RtContext ctx,int width,int height,List<byte[]> levels,Map<PageKey,RtMaterialPageTexture[]> cache) {
        List<byte[]> cropped=MaterialPageCrop.levels(levels,width,height);
        return cache.computeIfAbsent(new PageKey(cropped),key -> {
            int bc5=org.lwjgl.vulkan.VK10.VK_FORMAT_BC5_UNORM_BLOCK;
            var rg=new RtMaterialPageTexture(ctx,width,height,MaterialPageBc5.encode(cropped,width,height,0),"material channel "+cache.size()+" rg",bc5);
            try {
                return new RtMaterialPageTexture[]{rg,
                        new RtMaterialPageTexture(ctx,width,height,MaterialPageBc5.encode(cropped,width,height,2),"material channel "+cache.size()+" ba",bc5)};
            } catch(Throwable failure) { rg.destroy(); throw failure; }
        });
    }

    /**
     * Relief height corners for one bilinear read. smooth_parallax.slang's smoothHeight reads the page
     * with the NEAREST atlas sampler at texel centres cell-1 and cell, then interpolates them, with
     * all-zero heights reading as full height. Texel (x+i, y+j) here holds that corner value, height
     * texel (i-1, j-1) with the zero sentinel, wrapped inside the tile for i or j = 0 (the last
     * column/row lands in the gutter). A LINEAR read at grid+0.5 then equals smoothHeight's height
     * inside the tile, with a quarter of the fetches; only the outermost texel ring differs slightly.
     */
    private static void writeHeightBox(byte[] box,byte[] normalAo,int pageWidth,int pageHeight,Candidate c) {
        int w=c.storageWidth,h=c.storageHeight;
        for(int j=0;j<=h;j++) {
            int y=c.y+j; if(y>=pageHeight)continue;
            int sy=c.y+Math.floorMod(j-1,h);
            for(int i=0;i<=w;i++) {
                int x=c.x+i; if(x>=pageWidth)continue;
                int sx=c.x+Math.floorMod(i-1,w);
                int height=normalAo[(sy*pageWidth+sx)*4+3]&255;
                box[y*pageWidth+x]=(byte)(height==0?255:height);
            }
        }
    }

    public void bindPages(RtPipeline pipeline, long sampler, long heightBoxSampler) {
        for (Page page : pages) {
            pipeline.setMaterialPage(page.index(), page.surface0().view(), page.normalAo().view(),
                    page.surface1().view(), page.heightBox().view(), page.surface0B().view(),
                    page.normalAoB().view(), page.surface1B().view(), sampler, heightBoxSampler);
        }
    }

    public Entry entry(TextureAtlasSprite sprite) {
        return entries.getOrDefault(sprite, fallback);
    }

    /** Read-only borrowed images: caller must release views before reset() destroys this epoch. */
    /** {@code views}: surface0, normalAo, surface1 RG halves, then their BA halves (BC5, see MaterialPageBc5). */
    public record PageImages(long[] views, int width, int height, int mips) {}
    public List<PageImages> pageImages() {
        return pages.stream().map(p -> new PageImages(new long[]{p.surface0.view(), p.normalAo.view(), p.surface1.view(),
                p.surface0B.view(), p.normalAoB.view(), p.surface1B.view()}, p.surface0.width, p.surface0.height, p.surface0.mipCount)).toList();
    }

    public Map<TextureAtlasSprite, Entry> preparedEntries() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(entries));
    }

    /** Per-image-frame page placement of animated sprites; every slot is filled. */
    public Map<TextureAtlasSprite, Entry[]> preparedAnimatedFrames() {
        Map<TextureAtlasSprite, Entry[]> complete = new IdentityHashMap<>();
        animatedFrames.forEach((sprite, frames) -> {
            if (java.util.Arrays.stream(frames).allMatch(java.util.Objects::nonNull)) complete.put(sprite, frames);
        });
        return complete;
    }

    public Map<Identifier, Entry> preparedResourceEntries() {
        return Collections.unmodifiableMap(new HashMap<>(resourceEntries));
    }

    public void destroy() {
        reset();
    }

    private Entry fallbackFor(TextureAtlasSprite sprite) {
        return new Entry(0, fallback.pageIndex(), 0,
                fallback.materialU, fallback.materialV, fallback.materialDu, fallback.materialDv,
                sprite.getU0(), sprite.getV0(), inverseExtent(sprite.getU1() - sprite.getU0()),
                inverseExtent(sprite.getV1() - sprite.getV0()), RtMaterialDesc.EmissionSummary.NONE,
                RtMaterialDesc.EmissionSummary.NONE, null, null);
    }

    private record Decoded(List<RtMaterialTextureData.Level> levels,
                           RtMaterialDesc.EmissionSummary emissionSummary,
                           RtMaterialDesc.EmissionSummary overrideEmissionSummary,
                           RtEmissionGrid emissionGrid, RtEmissionGrid overrideEmissionGrid) {
    }

    /** Reduce a per-texel emission mask to the light collector's summary grid (premultiplied by albedo). */
    private static RtEmissionGrid emissionGrid(float[] linearAlbedo, float[] mask, int width, int height) {
        RtEmissionGrid.Builder builder = new RtEmissionGrid.Builder(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                float weight = Math.max(0.0f, Math.min(1.0f, mask[pixel]));
                int i = pixel * 4;
                builder.add(x, y, linearAlbedo[i] * weight, linearAlbedo[i + 1] * weight,
                        linearAlbedo[i + 2] * weight, weight);
            }
        }
        return builder.build();
    }

    /**
     * True when the LabPBR height map (the alpha channel of the {@code _n} texture) actually varies across
     * the sprite. Packs routinely save {@code _n} with a constant alpha when they ship no height data;
     * treating that as a real height field would displace the entire material instead of leaving it flat.
     */
    private static boolean heightVaries(NativeImage normal, int width, int height, Candidate candidate) {
        int first = sample(normal, 0, 0, width, height, candidate.blockSprite(), candidate.frame);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (sample(normal, x, y, width, height, candidate.blockSprite(), candidate.frame) != first) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Decoded decode(Candidate candidate) throws Exception {
        NativeImage spec = (candidate.features & RtMaterialRegistry.FEATURE_SPEC) != 0
                ? load(candidate.specLocation) : null;
        NativeImage normal = (candidate.features & RtMaterialRegistry.FEATURE_NORMAL) != 0
                ? load(candidate.normalLocation) : null;
        NativeImage resourceAlbedo = candidate.blockSprite() ? null : load(candidate.albedoLocation);
        try {
            int width = candidate.width;
            int height = candidate.height;
            float[] surface0 = new float[width * height * 4];
            float[] normalAo = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] linearAlbedo = new float[surface0.length];
            float[] authoredEmission = spec != null ? new float[width * height] : null;
            NativeImage albedo = candidate.blockSprite()
                    ? ((SpriteContentsAccessor) candidate.sprite.contents()).caustica$originalImage()
                    : resourceAlbedo;
            boolean hasHeight = normal != null && heightVaries(normal, width, height, candidate);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int albedoPixel = albedo == null ? -1 : candidate.blockSprite() ? albedo.getPixel(x, Math.min(albedo.getHeight() - 1, y + candidate.frame * height)) : sample(albedo, x, y, width, height, false);
                    float ar = RtMaterialTextureData.srgbToLinear(ARGB.red(albedoPixel));
                    float ag = RtMaterialTextureData.srgbToLinear(ARGB.green(albedoPixel));
                    float ab = RtMaterialTextureData.srgbToLinear(ARGB.blue(albedoPixel));
                    float aa = ARGB.alpha(albedoPixel) / 255.0f;
                    linearAlbedo[i] = ar;
                    linearAlbedo[i + 1] = ag;
                    linearAlbedo[i + 2] = ab;
                    linearAlbedo[i + 3] = aa;
                    if (spec != null) {
                        int pixel = sample(spec, x, y, width, height, candidate.blockSprite(), candidate.frame);
                        RtLabPbr.Specular decoded = RtLabPbr.decodeSpec(
                                ARGB.red(pixel) / 255.0f, ARGB.green(pixel) / 255.0f,
                                ARGB.blue(pixel) / 255.0f, ARGB.alpha(pixel) / 255.0f, ar, ag, ab);
                        surface0[i] = decoded.roughness();
                        surface0[i + 1] = decoded.metalness();
                        surface0[i + 2] = decoded.emission();
                        authoredEmission[y * width + x] = decoded.emission() * aa;
                        surface0[i + 3] = decoded.sss();
                        surface1[i] = decoded.f0r();
                        surface1[i + 1] = decoded.f0g();
                        surface1[i + 2] = decoded.f0b();
                        // Override masks replace this channel later, and shader decode guards that flag.
                        surface1[i + 3] = RtLabPbr.decodePorosity(ARGB.blue(pixel) / 255.0f);
                    } else {
                        surface0[i] = 1.0f;
                        surface1[i] = surface1[i + 1] = surface1[i + 2] = 0.04f;
                    }
                    if (normal != null) {
                        int pixel = sample(normal, x, y, width, height, candidate.blockSprite(), candidate.frame);
                        float nx = ARGB.red(pixel) / 127.5f - 1.0f;
                        float ny = ARGB.green(pixel) / 127.5f - 1.0f;
                        float lengthSq = nx * nx + ny * ny;
                        if (lengthSq > 1.0f) {
                            float invLength = 1.0f / (float) Math.sqrt(lengthSq);
                            nx *= invLength;
                            ny *= invLength;
                        }
                        normalAo[i] = nx * 0.5f + 0.5f;
                        normalAo[i + 1] = ny * 0.5f + 0.5f;
                        normalAo[i + 2] = ARGB.blue(pixel) / 255.0f;
                        normalAo[i + 3] = ARGB.alpha(pixel) / 255.0f;
                    } else {
                        normalAo[i] = normalAo[i + 1] = 0.5f;
                        normalAo[i + 2] = 1.0f;
                        // LabPBR keeps the height map in the _n alpha channel, so a missing normal texture
                        // must read as flat. Leaving this at the array default (0) would place every pixel of
                        // a height-less material at the deepest point and fake a displacement everywhere.
                        normalAo[i + 3] = 1.0f;
                    }
                }
            }
            if (normal != null && !hasHeight) {
                // Constant alpha across the whole sprite means the pack shipped no usable height map.
                // Flatten it so parallax never displaces a height-less material by a uniform amount.
                for (int i = 3; i < normalAo.length; i += 4) {
                    normalAo[i] = 1.0f;
                }
            }
            RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
            RtEmissionGrid grid = null;
            RtEmissionGrid overrideGrid = null;
            if (spec != null) {
                emissionSummary = RtEmissionHeuristic.summarize(linearAlbedo, authoredEmission);
                grid = emissionGrid(linearAlbedo, authoredEmission, width, height);
            }
            RtMaterialDesc.EmissionSummary overrideEmissionSummary = RtMaterialDesc.EmissionSummary.NONE;
            boolean heuristic = (candidate.features & RtMaterialRegistry.FEATURE_HEURISTIC_EMISSION) != 0;
            boolean overrideMask = (candidate.features & RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION) != 0;
            if (heuristic || overrideMask) {
                RtEmissionHeuristic.Result emission = RtEmissionHeuristic.compile(linearAlbedo);
                float[] mask = emission.mask();
                for (int pixel = 0; pixel < mask.length; pixel++) {
                    if (heuristic) surface0[pixel * 4 + 2] = mask[pixel];
                    if (overrideMask) surface1[pixel * 4 + 3] = mask[pixel];
                }
                if (heuristic) emissionSummary = emission.summary();
                if (overrideMask) overrideEmissionSummary = emission.summary();
                RtEmissionGrid maskGrid = emissionGrid(linearAlbedo, mask, width, height);
                if (heuristic) grid = maskGrid;
                if (overrideMask) overrideGrid = maskGrid;
            }
            int maxLod = maxLodFor(width, height);
            return new Decoded(RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normalAo, surface1), maxLod), emissionSummary, overrideEmissionSummary,
                    grid, overrideGrid);
        } finally {
            if (spec != null) spec.close();
            if (normal != null) normal.close();
            if (resourceAlbedo != null) resourceAlbedo.close();
        }
    }

    private static final class PagePixels {
        final List<byte[]> surface0;
        final List<byte[]> normalAo;
        final List<byte[]> surface1;
        final int pageSize;

        PagePixels(int pageSize, int pageHeight, int mipCount) {
            this.pageSize = pageSize;
            surface0 = allocate(pageSize, pageHeight, mipCount, 255, 0, 0, 0);
            normalAo = allocate(pageSize, pageHeight, mipCount, 128, 128, 255, 0);
            surface1 = allocate(pageSize, pageHeight, mipCount, 10, 10, 10, 0);
        }

        void writeFallback() {
            // Neutral arrays already contain the fallback texel and its replicated surroundings.
        }

        void write(Candidate candidate, PackedMaterialTile tile) {
            try(var input=tile.open()) {
                int sw=candidate.storageWidth, sh=candidate.storageHeight;
                for(int mip=0;mip<tile.levels;mip++) {
                    int width=Math.max(1,pageSize>>mip), cx=candidate.x>>mip, cy=candidate.y>>mip;
                    int gutter=Math.max(1,GUTTER>>mip);
                    for(byte[] target:new byte[][]{surface0.get(mip),normalAo.get(mip),surface1.get(mip)}) {
                        byte[] source=input.readNBytes(Math.multiplyExact(Math.multiplyExact(sw,sh),4));
                        if(source.length!=sw*sh*4)throw new java.io.EOFException("Truncated material tile");
                        blitPacked(target,width,cx,cy,gutter,sw,sh,source);
                    }
                    sw=Math.max(1,(sw+1)/2); sh=Math.max(1,(sh+1)/2);
                }
            } catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }
        private static void blitPacked(byte[] dst,int dw,int cx,int cy,int gutter,int sw,int sh,byte[] src) {
            for(int dy=-gutter;dy<sh+gutter;dy++) {
                int ty=cy+dy; if(ty<0||ty>=dst.length/(dw*4))continue;
                int sy=Math.max(0,Math.min(sh-1,dy));
                for(int dx=-gutter;dx<sw+gutter;dx++) {
                    int tx=cx+dx;if(tx<0||tx>=dw)continue;
                    int sx=Math.max(0,Math.min(sw-1,dx));
                    System.arraycopy(src,(sy*sw+sx)*4,dst,(ty*dw+tx)*4,4);
                }
            }
        }

        private static List<byte[]> allocate(int size, int height, int mipCount, int r, int g, int b, int a) {
            List<byte[]> result = new ArrayList<>(mipCount);
            int width = size;
            for (int mip = 0; mip < mipCount; mip++) {
                byte[] values = new byte[width * height * 4];
                for (int i = 0; i < values.length; i += 4) {
                    values[i] = (byte) r;
                    values[i + 1] = (byte) g;
                    values[i + 2] = (byte) b;
                    values[i + 3] = (byte) a;
                }
                result.add(values);
                width = Math.max(1, width / 2);
                height = Math.max(1, height / 2);
            }
            return result;
        }

    }

    static List<TextureAtlasSprite> blockSprites() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        return sprites != null ? sprites : List.of();
    }

    private static Map<Identifier, Integer> discoverEntityMaterialResources(
            List<TextureAtlasSprite> blockSprites, RtMaterialOverrides overrides) {
        Set<Identifier> blockNames = new HashSet<>();
        for (TextureAtlasSprite sprite : blockSprites) {
            if (sprite != null) blockNames.add(sprite.contents().name());
        }
        Map<Identifier, Integer> result = new LinkedHashMap<>();
        Map<Identifier, Resource> authored = Minecraft.getInstance().getResourceManager().listResources(
                "textures", id -> id.getPath().endsWith("_s.png") || id.getPath().endsWith("_n.png"));
        List<Identifier> ordered = new ArrayList<>(authored.keySet());
        ordered.sort(Comparator.comparing(Identifier::toString));
        for (Identifier material : ordered) {
            String path = material.getPath();
            boolean spec = path.endsWith("_s.png");
            String albedoPath = path.substring(0, path.length() - 6) + ".png";
            Identifier albedo = Identifier.fromNamespaceAndPath(material.getNamespace(), albedoPath);
            Identifier name = logicalTextureName(albedo);
            if (blockNames.contains(name) || !resourceExists(albedo)) continue;
            result.merge(albedo, spec ? RtMaterialRegistry.FEATURE_SPEC
                    : RtMaterialRegistry.FEATURE_NORMAL, (a, b) -> a | b);
        }
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            if (rule.block() != null || blockNames.contains(rule.sprite())) {
                continue;
            }
            Identifier albedo = textureLocation(rule.sprite());
            if (resourceExists(albedo)) {
                int feature = rule.emissionStrength() != null && rule.emissionStrength() > 0.0f
                        ? RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION : 0;
                result.merge(albedo, feature, (a, b) -> a | b);
            }
        }
        return result;
    }

    private static Identifier sibling(Identifier name, String suffix) {
        return Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + suffix);
    }

    private static Identifier textureLocation(Identifier logicalName) {
        return Identifier.fromNamespaceAndPath(logicalName.getNamespace(),
                "textures/" + logicalName.getPath() + ".png");
    }

    /** Strip the {@code textures/} prefix and {@code .png} suffix shared by all material resource paths. */
    static Identifier logicalTextureName(Identifier textureLocation) {
        String path = textureLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return Identifier.fromNamespaceAndPath(textureLocation.getNamespace(), path);
    }

    static int maxLodFor(int width, int height) {
        return Math.min(MAX_VALID_LOD, 31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
    }

    private static Identifier siblingTexture(Identifier albedo, String suffix) {
        String path = albedo.getPath();
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return Identifier.fromNamespaceAndPath(albedo.getNamespace(), path + suffix + ".png");
    }

    private static boolean resourceExists(Identifier location) {
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    private static NativeImage load(Identifier location) throws Exception {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream input = resource.get().open()) {
            return NativeImage.read(input);
        }
    }

    private static int sample(NativeImage image, int x, int y, int width, int height, boolean firstFrameOnly) {
        return sample(image, x, y, width, height, firstFrameOnly, 0);
    }

    /** Square-frame strip sampling; a map with fewer frames than the albedo holds its last frame. */
    private static int sample(NativeImage image, int x, int y, int width, int height, boolean framed, int frame) {
        int sampledHeight = framed ? Math.min(image.getHeight(), image.getWidth()) : image.getHeight();
        int offset = framed ? Math.min(frame, image.getHeight() / Math.max(1, sampledHeight) - 1) * sampledHeight : 0;
        int sx = Math.min(image.getWidth() - 1, x * image.getWidth() / width);
        int sy = Math.min(sampledHeight - 1, y * sampledHeight / height);
        return image.getPixel(sx, offset + sy);
    }

    static float inverseExtent(float extent) {
        return Math.abs(extent) > 1.0e-12f ? 1.0f / extent : 0.0f;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    // Synchronized: decode failures can now arrive from parallel workers.
    private synchronized void warnOnce(String message, Throwable throwable) {
        if (!loggedFailure) {
            loggedFailure = true;
            CausticaMod.LOGGER.warn(message, throwable);
        }
    }
}
