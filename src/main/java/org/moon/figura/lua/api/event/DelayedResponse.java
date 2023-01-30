package org.moon.figura.lua.api.event;

import org.luaj.vm2.LuaValue;
import org.moon.figura.lua.LuaWhitelist;

import java.util.concurrent.CompletableFuture;

@LuaWhitelist
public class DelayedResponse extends CompletableFuture<LuaValue> {
    public DelayedResponse() {
        super();
    }

    @LuaWhitelist
    public boolean complete(LuaValue value) {
        return super.complete(value);
    }

    @LuaWhitelist
    public boolean isDone() {
        return super.isDone();
    }
}
