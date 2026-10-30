package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import java.util.Random;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TorchGuidanceTest {
    @Test void gridIsBoundedAndSelectsNearestPublishedHint() {
        float[] grid = TorchGuidanceGrid.build(new float[]{2,2,2, 10,2,2});
        assertEquals(65536, grid.length * Float.BYTES);
        assertEquals(2, grid[0]);
        assertEquals(TorchGuidanceGrid.RADIUS, grid[3]);
        assertEquals(10, grid[2 * 4]);
        assertEquals(0, grid[grid.length - 1]); // distant cells retain pure cosine
        assertArrayEquals(new float[grid.length], TorchGuidanceGrid.build(new float[0]));
        assertThrows(IllegalArgumentException.class, () -> TorchGuidanceGrid.build(new float[]{0}));
    }

    @Test void largeWorldPositionsAreReducedBeforeFloatConversion() {
        double world = 29_999_999.5;
        int origin = (int)Math.floor(world / 16) * 16 - 32;
        float local = (float)(world - origin);
        float[] grid = TorchGuidanceGrid.build(new float[]{local,2,2});
        int cell = (int)Math.floor(local / TorchGuidanceGrid.CELL_SIZE);
        assertEquals(world, origin + (double)grid[cell * 4]);
        assertEquals(TorchGuidanceGrid.RADIUS, grid[cell * 4 + 3]);
    }

    @Test void proposalKeepsLambertianEnergyAndActuallySamplesSmallLight() {
        // Independent double-precision integration of the shader's 50/50 cosine/cone proposal.
        // An aligned spherical emitter at distance 5 has exact Lambertian contribution (r/d)^2.
        double r = .65, distance = 5, sin2 = r*r/(distance*distance);
        double coneCos = Math.sqrt(1-sin2), omega = 2*Math.PI*(1-coneCos);
        Random random = new Random(552041);
        int count = 400_000, hits = 0;
        double energy = 0, lightEnergy = 0;
        for (int i=0; i<count; i++) {
            double cosine = random.nextBoolean() ? 1-random.nextDouble()*(1-coneCos)
                    : Math.sqrt(1-random.nextDouble());
            boolean inCone = cosine >= coneCos;
            double pdf = .5*(cosine/Math.PI + (inCone ? 1/omega : 0));
            double weight = cosine/Math.PI/pdf;
            assertTrue(Double.isFinite(weight) && weight >= 0 && weight <= 2);
            energy += weight;
            if (inCone) { lightEnergy += weight; hits++; }
        }
        assertEquals(1, energy/count, .008); // constant environment unchanged
        assertEquals(sin2, lightEnergy/count, .00015); // no duplicate light energy
        assertTrue(hits/(double)count > .49); // cosine-only probability is just 0.0169
    }

    @Test void belowSurfaceProposalHasZeroContributionWithoutResamplingBias() {
        // Cone points entirely below the receiver. Half the samples have zero BRDF;
        // the cosine half has weight exactly two, preserving unit energy.
        double coneCos = Math.sqrt(1-.65*.65/25), omega=2*Math.PI*(1-coneCos);
        assertEquals(0, lambertWeight(-1, 1, coneCos, omega));
        assertEquals(2, lambertWeight(.7, -.7, coneCos, omega));
        assertEquals(1, .5*lambertWeight(.7,-.7,coneCos,omega));
    }
    private static double lambertWeight(double nl, double axisDot, double coneCos, double omega) {
        double cosine=Math.max(0,nl)/Math.PI;
        double pdf=.5*(cosine+(axisDot>=coneCos?1/omega:0));
        return pdf>0?cosine/pdf:0;
    }

    @Test void tiltedAndClippedConesPreserveTheWholeHemisphereIntegral() {
        double sin2=.65*.65/25, cmin=Math.sqrt(1-sin2), omega=2*Math.PI*(1-cmin);
        Random random=new Random(20261020);
        for(double angle:new double[]{0,Math.PI/3,Math.PI/2,2*Math.PI/3,Math.PI}) {
            double ax=Math.sin(angle), az=Math.cos(angle), total=0;
            int count=200_000;
            for(int i=0;i<count;i++) {
                double c, x, z;
                double phi=2*Math.PI*random.nextDouble();
                if(random.nextBoolean()) {
                    c=1-random.nextDouble()*(1-cmin);
                    double radial=Math.sqrt(1-c*c);
                    x=ax*c+az*radial*Math.cos(phi);
                    z=az*c-ax*radial*Math.cos(phi);
                } else {
                    z=Math.sqrt(1-random.nextDouble());
                    x=Math.sqrt(1-z*z)*Math.cos(phi);
                }
                total+=lambertWeight(z,ax*x+az*z,cmin,omega);
            }
            assertEquals(1,total/count,.012,"cone angle "+angle);
        }
    }

    @Test void samplerAddsNoTraceOrArtificialLightTerm() throws Exception {
        String shader=Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String sampler=shader.substring(shader.indexOf("float3 torchGuidedDiffuse("),shader.indexOf("// Soft shadows: sample"));
        assertFalse(sampler.contains("traceRadianceReordered("));
        assertFalse(sampler.contains("visibility("));
        assertFalse(sampler.contains("L +="));
        assertTrue(sampler.contains("cosinePdf / pdf"));
        assertTrue(shader.contains("diffAlb * guideWeight / (1.0 - ps)"));
    }
}
