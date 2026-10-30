package net.minecomputers.network;

import net.minecomputers.client.ClientScreens;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers the mod's network packets. */
public final class ModNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(LaptopSnapshot.TYPE, LaptopSnapshot.STREAM_CODEC,
                (payload, context) -> ClientScreens.onSnapshot(payload));
        registrar.playToClient(ConsoleSync.TYPE, ConsoleSync.STREAM_CODEC,
                (payload, context) -> ClientScreens.onConsole(payload));
        registrar.playToClient(LaptopNotice.TYPE, LaptopNotice.STREAM_CODEC,
                (payload, context) -> ClientScreens.onNotice(payload));
        registrar.playToClient(ScreenUpdate.TYPE, ScreenUpdate.STREAM_CODEC,
                (payload, context) -> ClientScreens.onScreenUpdate(payload));
        registrar.playToServer(LaptopAction.TYPE, LaptopAction.STREAM_CODEC, ServerHandlers::handleAction);
        registrar.playToServer(LaptopUpload.TYPE, LaptopUpload.STREAM_CODEC, ServerHandlers::handleUpload);
    }

    private ModNetwork() {
    }
}
