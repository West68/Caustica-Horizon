package dev.comfyfluffy.caustica.enhanced.test;

import java.nio.file.*;
import java.util.*;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.EnhancedOptionsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Native event-driven UI check, with Sodium installed. Does not use desktop automation. */
final class EnhancedSettingsCheck {
    static List<AbstractWidget> widgets(GuiEventListener item) {
        var out=new ArrayList<AbstractWidget>();
        if(item instanceof AbstractWidget w) out.add(w);
        if(item instanceof ContainerEventHandler container)
            for(var child:container.children()) out.addAll(widgets(child));
        return out;
    }
    static void click(Screen screen,String translation) {
        String text=Component.translatable(translation).getString();
        var widget=widgets(screen).stream().filter(w->w.getMessage().getString().equals(text)).findFirst().orElseThrow(()->new AssertionError("Missing button: "+text));
        double x=widget.getX()+widget.getWidth()/2.0,y=widget.getY()+widget.getHeight()/2.0;
        if(!screen.mouseClicked(new MouseButtonEvent(x,y,new MouseButtonInfo(0,0)),false)) throw new AssertionError("Click rejected: "+text);
        screen.mouseReleased(new MouseButtonEvent(x,y,new MouseButtonInfo(0,0)));
    }
    static void shot(ClientGameTestContext c,Path out,String name) throws Exception {
        c.waitTicks(5);Files.copy(c.takeScreenshot(name),out.resolve(name+".png"),StandardCopyOption.REPLACE_EXISTING);
    }
    static void run(ClientGameTestContext c,Path out) throws Exception {
        if(Boolean.getBoolean("ce.verifyUiRestart") && Math.abs(CausticaConfig.Rt.Look.BRIGHTNESS.value()-1.17f)>.001f)
            throw new AssertionError("Saved brightness did not survive restart");
        if(Boolean.getBoolean("ce.uiRestartOnly")) {
            c.runOnClient(mc->{CausticaConfig.Rt.Look.BRIGHTNESS.set(1f);CausticaConfig.save();mc.gui.setScreen(new EnhancedOptionsScreen(new TitleScreen()));});
            shot(c,out,"settings-restarted-home");
            c.runOnClient(mc->mc.gui.setScreen(new EnhancedOptionsScreen(new TitleScreen(),"color")));
            shot(c,out,"settings-restarted-color");
            c.runOnClient(mc->mc.gui.setScreen(null));
            Files.writeString(out.resolve("settings-restart.json"),"{\"savedBrightnessRestored\":true,\"neutralRestoredForVisualTest\":true}");
            return;
        }
        boolean sodium=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        if(!sodium) throw new AssertionError("Sodium absent from UI integration replica");
        String oldLang=c.computeOnClient(mc->mc.getLanguageManager().getSelected());
        int oldScale=c.computeOnClient(mc->mc.options.guiScale().get());
        var log=new ArrayList<String>();
        for(String language:new String[]{"zh_cn","en_us"}) {
            var reload=c.computeOnClient(mc->{mc.getLanguageManager().setSelected(language);mc.options.languageCode=language;return mc.reloadResourcePacks();});
            c.waitFor(mc->reload.isDone(),1200);reload.join();
            c.runOnClient(mc->mc.gui.setScreen(new OptionsScreen(new TitleScreen(),mc.options,false)));
            shot(c,out,"settings-"+language+"-entry");
            c.runOnClient(mc->click(mc.gui.screen(),"caustica.enhanced.entry"));
            if(!c.computeOnClient(mc->mc.gui.screen() instanceof EnhancedOptionsScreen)) throw new AssertionError("Independent entry failed");
            shot(c,out,"settings-"+language+"-home");
            for(String category:new String[]{"color","clouds","material","rt","fg","advanced"}) {
                c.runOnClient(mc->mc.gui.setScreen(new EnhancedOptionsScreen(new TitleScreen(),category)));
                shot(c,out,"settings-"+language+"-"+category);
                log.add(language+":"+category+":"+c.computeOnClient(mc->widgets(mc.gui.screen()).stream().map(w->w.getMessage().getString()).toList()));
            }
        }
        // A real slider mouse event must mutate its bound runtime setting.
        c.runOnClient(mc->mc.gui.setScreen(new EnhancedOptionsScreen(new TitleScreen(),"color")));
        c.waitTicks(5);
        c.runOnClient(mc->{
            var slider=widgets(mc.gui.screen()).stream().filter(w->w instanceof AbstractSliderButton && w.getMessage().getString().contains("Brightness")).findFirst().orElseThrow();
            slider.onClick(new MouseButtonEvent(slider.getX()+slider.getWidth()*.8,slider.getY()+10,new MouseButtonInfo(0,0)),false);
        });c.waitTicks(30);
        if(Math.abs(CausticaConfig.Rt.Look.BRIGHTNESS.value()-1f)<.01f) throw new AssertionError("Brightness slider did not update config");
        // Test native layout at another UI scale and smaller drawable size, then restore the requested output.
        c.runOnClient(mc->{mc.options.guiScale().set(3);mc.resizeGui();});
        BaselineTest.size(c,1280,720);
        c.runOnClient(mc->mc.gui.setScreen(new OptionsScreen(new TitleScreen(),mc.options,false)));
        shot(c,out,"settings-small-entry");
        c.runOnClient(mc->click(mc.gui.screen(),"caustica.enhanced.entry"));shot(c,out,"settings-small-home");
        c.runOnClient(mc->{mc.options.guiScale().set(oldScale);mc.resizeGui();});
        boolean ultra=System.getProperty("ce.mode","").equals("1440p-ultra");BaselineTest.size(c,ultra?2560:1920,ultra?1440:1080);
        var restore=c.computeOnClient(mc->{mc.getLanguageManager().setSelected(oldLang);mc.options.languageCode=oldLang;return mc.reloadResourcePacks();});
        c.waitFor(mc->restore.isDone(),1200);restore.join();
        c.runOnClient(mc->{dev.comfyfluffy.caustica.client.RtVideoOptions.applyVisualPreset(true);CausticaConfig.Rt.Look.BRIGHTNESS.set(Boolean.getBoolean("ce.persistUi")?1.17f:1f);CausticaConfig.save();mc.gui.setScreen(null);});
        Files.copy(CausticaConfig.configPath(),out.resolve("settings-saved.toml"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(out.resolve("settings-check.json"),"{\"sodium\":true,\"entryClicked\":true,\"sliderChangedConfig\":true,\"restartVerified\":"+Boolean.getBoolean("ce.verifyUiRestart")+",\"persistBrightness\":"+CausticaConfig.Rt.Look.BRIGHTNESS.value()+"}");
        Files.write(out.resolve("settings-labels.txt"),log);
    }
}
