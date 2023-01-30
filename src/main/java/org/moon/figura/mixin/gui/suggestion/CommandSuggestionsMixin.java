package org.moon.figura.mixin.gui.suggestion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.moon.figura.FiguraMod;
import org.moon.figura.avatar.Avatar;
import org.moon.figura.avatar.AvatarManager;
import org.moon.figura.avatar.Badges;
import org.moon.figura.config.Config;
import org.moon.figura.ducks.CommandSuggestionsAccessor;
import org.moon.figura.utils.ColorUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin implements CommandSuggestionsAccessor {
    @Unique
    boolean shouldShowBadges = false;
    @Unique
    boolean useFiguraSuggester = false;
    @Unique
    int cachedEnlargement = -1;
    @Unique
    CompletableFuture<SuggestionBehaviour> currentBehaviour;

    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;
    @Final
    @Shadow
    private List<FormattedCharSequence> commandUsage;
    @Shadow
    private int commandUsageWidth;
    @Shadow
    private int commandUsagePosition;
    @Shadow
    private boolean allowSuggestions;
    @Shadow
    boolean keepSuggestions;
    @Final
    @Shadow
    EditBox input;
    @Final
    @Shadow
    Screen screen;


    @Shadow @Final
    Font font;

    @Shadow public abstract void updateCommandInfo();

    @Override
    public void figura$setUseFiguraSuggester(boolean use) {
        useFiguraSuggester = use;
    }

    @Override
    public boolean figura$shouldShowFiguraBadges() {
        return shouldShowBadges;
    }

    public Font figura$getFont() {
        return font;
    }

    @Unique
    private int getEnlargement() {
        if (cachedEnlargement == -1) {
            cachedEnlargement = this.font.width(Badges.System.DEFAULT.badge.copy().withStyle(Style.EMPTY.withFont(Badges.FONT))) + this.font.width(" ");
        }
        return cachedEnlargement;
    }

    @Unique
    private Component getLoadingText() {
        MutableComponent load = Component.empty();

        MutableComponent badge = Component.literal(Integer.toHexString(Math.abs(FiguraMod.ticks) % 16));
        badge.setStyle(Style.EMPTY.withColor(ColorUtils.rgbToInt(ColorUtils.Colors.DEFAULT.vec)).withFont(Badges.FONT));
        load.append(badge);
        load.append(" This avatar is loading suggestions");

        return load;
    }

    @SuppressWarnings("InvalidInjectorMethodSignature")  // the plugin keeps telling that it's wrong for some reason
    @Inject(
            method = "updateCommandInfo",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lnet/minecraft/client/gui/components/EditBox;getCursorPosition()I",
                    shift = At.Shift.AFTER
            ),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    public void useFigura(CallbackInfo ci, String string, StringReader stringReader, boolean bl2, int i) {
        if (!useFiguraSuggester || keepSuggestions) {
            return;
        }

        shouldShowBadges = false;

        if (Config.CHAT_AUTOCOMPLETE.asInt() == 2) {
            return;
        }

        Avatar avatar = AvatarManager.getAvatarForPlayer(FiguraMod.getLocalPlayerUUID());
        if (avatar == null || avatar.luaRuntime == null)
            return;

        if (currentBehaviour != null) {
            currentBehaviour.cancel(true);
        }

        currentBehaviour = avatar.chatAutocompleteEvent(string, i);

        if (currentBehaviour == null) {
            return;
        }

        if (!currentBehaviour.isDone()) {
            commandUsagePosition = 0;
            commandUsageWidth = font.width(getLoadingText());
        }


        pendingSuggestions = currentBehaviour.thenApply(behave -> {
            commandUsageWidth = screen.width;
            commandUsagePosition = 0;
            commandUsage.clear();

            shouldShowBadges = Config.CHAT_AUTOCOMPLETE.asInt() == 1;

            Suggestions suggestions;

            if (behave instanceof AcceptBehaviour accepting) {
                suggestions = accepting.suggest();

                if (suggestions.isEmpty()) {
                    commandUsage.add(FormattedCharSequence.forward("This avatar did not provide any suggestion", Style.EMPTY));
                }
            }
            else {
                String usage;
                suggestions = Suggestions.merge("", Collections.emptyList());
                if (behave instanceof HintBehaviour hint) {
                    usage = hint.hint();
                    commandUsagePosition = Mth.clamp(input.getScreenX(hint.pos()), 0, this.input.getScreenX(0) + this.input.getInnerWidth() - i);
                    commandUsageWidth = font.width(usage);
                }
                else if (behave instanceof RejectBehaviour reject) {
                    usage = reject.err();
                }
                else {
                    throw new RuntimeException("Unexpected " + behave.getClass().getName());
                }

                if (shouldShowBadges) {
                    MutableComponent component = Component.empty();
                    MutableComponent badge = Badges.System.DEFAULT.badge.copy();
                    badge.setStyle(Style.EMPTY.withColor(ColorUtils.rgbToInt(ColorUtils.Colors.DEFAULT.vec)).withFont(Badges.FONT));
                    component.append(badge);
                    component.append(" ");
                    component.append(usage);

                    commandUsage.add(component.getVisualOrderText());
                    commandUsageWidth += getEnlargement();
                } else {
                    commandUsage.add(FormattedCharSequence.forward(usage, Style.EMPTY));
                }
            }

            if (commandUsage.isEmpty()) {
                commandUsageWidth = 0;
            }

            return suggestions;
        });

        pendingSuggestions.thenRun(() -> {
            if (allowSuggestions && Minecraft.getInstance().options.autoSuggestions().get()) {
                ((CommandSuggestions) (Object) this).showSuggestions(false);
            }
        });

        currentBehaviour.exceptionally(exc -> {
            useFiguraSuggester = false;
            try {
                updateCommandInfo();
            }
            finally {
                useFiguraSuggester = true;
            }
            return null;
        });

        ci.cancel();
    }

    @ModifyArg(
            method = "showSuggestions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/CommandSuggestions$SuggestionsList;<init>(Lnet/minecraft/client/gui/components/CommandSuggestions;IIILjava/util/List;Z)V"
            ),
            index = 3
    )
    public int enlargeToFitBadge(int v) {
        if (!shouldShowBadges) {
            return v;
        }
        return v + getEnlargement();
    }

    @Inject(
            method = "renderUsage",
            at = @At(
                    value = "HEAD"
            )
    )
    public void animateSpinner(PoseStack matrices, CallbackInfo ci) {
        if (currentBehaviour != null && !currentBehaviour.isDone()) {
            commandUsage.clear();
            commandUsage.add(getLoadingText().getVisualOrderText());
        }
    }
}
