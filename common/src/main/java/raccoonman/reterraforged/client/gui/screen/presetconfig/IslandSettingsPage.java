package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

public class IslandSettingsPage extends PresetEditorPage {
    private CycleButton<Boolean> enabled;
    private Slider spacing;
    private Slider chance;
    private Slider minRadius;
    private Slider maxRadius;
    private Slider jitter;
    private Slider rareBiomeChance;
    private Slider continentBuffer;

    private Slider coreRadius;
    private Slider shelfCenter;
    private Slider shelfWidth;
    private Slider shelfElevationShift;
    private Slider flattenStrength;
    private Slider slopeSteepness;
    private Slider inlandBlendAlpha;
    private Slider oceanBlendAlpha;

    public IslandSettingsPage(PresetConfigScreen screen, PresetEntry preset) {
        super(screen, preset);
    }

    @Override
    public Component title() {
        return Component.translatable(RTFTranslationKeys.GUI_ISLAND_SETTINGS_TITLE);
    }

    @Override
    public void init() {
        super.init();

        Preset preset = this.preset.getPreset();
        WorldSettings.Islands islands = preset.world().islands;
        WorldSettings.Islands.Shape shape = islands.shape;

        this.enabled = PresetWidgets.createToggle(islands.enabled, RTFTranslationKeys.GUI_BUTTON_ISLANDS_ENABLED, (button, value) -> {
            islands.enabled = value;
            this.regenerate();
        });
        this.spacing = PresetWidgets.createIntSlider(islands.spacing, 100, 3000, RTFTranslationKeys.GUI_SLIDER_ISLAND_SPACING, (slider, value) -> {
            islands.spacing = (int) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.chance = PresetWidgets.createFloatSlider(islands.chance, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_CHANCE, (slider, value) -> {
            islands.chance = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.minRadius = PresetWidgets.createFloatSlider(islands.minRadius, 8.0F, 512.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_MIN_RADIUS, (slider, value) -> {
            islands.minRadius = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.maxRadius = PresetWidgets.createFloatSlider(islands.maxRadius, 8.0F, 1024.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_MAX_RADIUS, (slider, value) -> {
            islands.maxRadius = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.jitter = PresetWidgets.createFloatSlider(islands.jitter, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_JITTER, (slider, value) -> {
            islands.jitter = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.rareBiomeChance = PresetWidgets.createFloatSlider(islands.rareBiomeChance, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_RARE_BIOME_CHANCE, (slider, value) -> {
            islands.rareBiomeChance = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.continentBuffer = PresetWidgets.createFloatSlider(islands.continentBuffer, 0.0F, 0.5F, RTFTranslationKeys.GUI_SLIDER_ISLAND_CONTINENT_BUFFER, (slider, value) -> {
            islands.continentBuffer = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });

        this.coreRadius = PresetWidgets.createFloatSlider(shape.coreRadius, 0.1F, 0.95F, RTFTranslationKeys.GUI_SLIDER_ISLAND_CORE_RADIUS, (slider, value) -> {
            shape.coreRadius = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.shelfCenter = PresetWidgets.createFloatSlider(shape.shelfCenter, 0.0F, 1.5F, RTFTranslationKeys.GUI_SLIDER_ISLAND_SHELF_CENTER, (slider, value) -> {
            shape.shelfCenter = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.shelfWidth = PresetWidgets.createFloatSlider(shape.shelfWidth, 0.01F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_SHELF_WIDTH, (slider, value) -> {
            shape.shelfWidth = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.shelfElevationShift = PresetWidgets.createFloatSlider(shape.shelfElevationShift, -0.5F, 0.5F, RTFTranslationKeys.GUI_SLIDER_ISLAND_SHELF_ELEVATION_SHIFT, (slider, value) -> {
            shape.shelfElevationShift = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.flattenStrength = PresetWidgets.createFloatSlider(shape.flattenStrength, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_FLATTEN_STRENGTH, (slider, value) -> {
            shape.flattenStrength = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.slopeSteepness = PresetWidgets.createFloatSlider(shape.slopeSteepness, 0.1F, 3.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_SLOPE_STEEPNESS, (slider, value) -> {
            shape.slopeSteepness = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.inlandBlendAlpha = PresetWidgets.createFloatSlider(shape.inlandBlendAlpha, 0.01F, 5.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_INLAND_BLEND_ALPHA, (slider, value) -> {
            shape.inlandBlendAlpha = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });
        this.oceanBlendAlpha = PresetWidgets.createFloatSlider(shape.oceanBlendAlpha, 0.01F, 5.0F, RTFTranslationKeys.GUI_SLIDER_ISLAND_OCEAN_BLEND_ALPHA, (slider, value) -> {
            shape.oceanBlendAlpha = (float) slider.scaleValue(value);
            this.regenerate();
            return value;
        });

        this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_ISLANDS));
        this.left.addWidget(this.enabled);
        this.left.addWidget(this.spacing);
        this.left.addWidget(this.chance);
        this.left.addWidget(this.minRadius);
        this.left.addWidget(this.maxRadius);
        this.left.addWidget(this.jitter);
        this.left.addWidget(this.rareBiomeChance);
        this.left.addWidget(this.continentBuffer);

        this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_ISLAND_SHAPE));
        this.left.addWidget(this.coreRadius);
        this.left.addWidget(this.shelfCenter);
        this.left.addWidget(this.shelfWidth);
        this.left.addWidget(this.shelfElevationShift);
        this.left.addWidget(this.flattenStrength);
        this.left.addWidget(this.slopeSteepness);
        this.left.addWidget(this.inlandBlendAlpha);
        this.left.addWidget(this.oceanBlendAlpha);
    }

    @Override
    public Optional<Page> previous() {
        return Optional.of(new WorldSettingsPage(this.screen, this.preset));
    }

    @Override
    public Optional<Page> next() {
        return Optional.of(new SurfaceSettingsPage(this.screen, this.preset));
    }
}