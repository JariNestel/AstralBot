package dev.erdragh.astralbot.neoforge.event

import net.minecraft.network.chat.Component
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.ICancellableEvent

class SystemMessageEvent(var message: Component) : Event(), ICancellableEvent