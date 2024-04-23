package dev.erdragh.astralbot.handlers

import com.mojang.authlib.GameProfile
import dev.erdragh.astralbot.*
import dev.erdragh.astralbot.config.AstralBotConfig
import dev.erdragh.astralbot.config.AstralBotTextConfig
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import java.awt.Color
import java.text.DecimalFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.jvm.optionals.getOrNull
import kotlin.math.min

/**
 * Wrapper class around the [MinecraftServer] to provide convenience
 * methods for fetching [GameProfile]s, sending Messages, acting
 * on the currently online players, etc.
 * @author Erdragh
 */
class MinecraftHandler(private val server: MinecraftServer) : ListenerAdapter() {
    private val playerNames = HashSet<String>(server.maxPlayers);
    private val notchPlayer = byName("Notch")?.let { ServerPlayer(this.server, this.server.allLevels.elementAt(0), it) }


    companion object {
        private val numberFormat = DecimalFormat("###.##")

        // Pattern for recognizing a URL, based off RFC 3986
        // Source: https://stackoverflow.com/questions/5713558/detect-and-extract-url-from-a-string
        private val urlPattern: Pattern = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE or Pattern.DOTALL
        )
    }

    /**
     * Gets all currently online players' names
     * @return a [Collection] of all currently online players'
     * names
     */
    fun getOnlinePlayers(): Collection<String> {
        return playerNames
    }

    /**
     * Adds the given [name] to the list and updates the Discord
     * presence of the Bot.
     * @param name the Name of the player that joined
     */
    fun onPlayerJoin(name: String) = synchronized(playerNames) {
        playerNames.add(name)
        updatePresence(playerNames.size)
    }

    /**
     * Removes the given [name] from the list and updates the Discord
     * presence of the Bot.
     * @param name the Name of the player that left
     */
    fun onPlayerLeave(name: String) = synchronized(playerNames) {
        playerNames.remove(name)
        updatePresence(playerNames.size)
    }


    /**
     * Stops the Minecraft [server] the same way the `/stop` command
     * does from inside Minecraft
     */
    fun stopServer() {
        server.halt(false)
    }

    /**
     * Formats the ticking performance information in a Human
     * Readable form.
     * @return the formatted String
     */
    fun tickReport(): String {
        // Idea from the TPSCommand in Forge
        return AstralBotTextConfig.TICK_REPORT.get().replace("{{mspt}}", numberFormat.format(server.averageTickTime))
            .replace(
                "{{tps}}", numberFormat.format(
                    min(
                        20.0,
                        1000.0 / server.averageTickTime
                    )
                )
            )
    }

    /**
     * Fetches the [GameProfile] of a given Minecraft user ID
     * @param id a User ID possibly associated with a Minecraft Account
     * @return the [GameProfile] of the given [id] or `null`
     * if:
     * - The given [id] doesn't have an actual Minecraft account
     *   associated with it
     * - The [server]'s profile cache hasn't been initialized yet
     */
    fun byUUID(id: UUID): GameProfile? {
        return server.profileCache?.get(id)?.getOrNull()
    }

    /**
     * Fetches the [GameProfile] of a given Minecraft username
     * @param name a username possibly associated with a Minecraft Account
     * @return the [GameProfile] of the given [name] or `null`
     * if:
     * - The given [name] doesn't have an actual Minecraft account
     *   associated with it
     * - The [server]'s profile cache hasn't been initialized yet
     */
    fun byName(name: String): GameProfile? {
        return server.profileCache?.get(name)?.getOrNull()
    }

    private fun formatComponentToMarkdown(comp: Component): String {
        return comp.toFlatList()
            .map {
                var formatted = it.string

                if (it.style.isBold) {
                    formatted = "**$formatted**"
                }
                if (it.style.isItalic) {
                    formatted = "_${formatted}_"
                }
                it.style.clickEvent?.let { clickEvent ->
                    if (clickEvent.action == ClickEvent.Action.OPEN_URL) {
                        formatted = "[$formatted](${clickEvent.value})"
                    }
                }

                val matcher = urlPattern.matcher(formatted)
                val replaced = matcher.replaceAll { match ->
                    val group = match.group()
                    if (AstralBotConfig.urlAllowed(group)) {
                        return@replaceAll group
                    } else {
                        return@replaceAll "`URL BLOCKED`"
                    }
                }
                formatted = replaced

                return@map formatted
            }
            .joinToString("")
    }

    private fun formatHoverText(text: Component): MessageEmbed {
        return EmbedBuilder()
            .setDescription(text.string)
            .let { builder: EmbedBuilder ->
                text.style.color?.value?.let { color -> builder.setColor(color) }
                builder
            }
            .build()
    }

    private fun formatHoverItems(stack: ItemStack, knownItems: MutableList<ItemStack>): MessageEmbed? {
        if (knownItems.contains(stack)) return null
        knownItems.add(stack)
        val tooltip = stack.getTooltipLines(notchPlayer, TooltipFlag.NORMAL).map(::formatComponentToMarkdown)
        return EmbedBuilder()
            .setTitle("${tooltip[0]} ${if (stack.count > 1) "(${stack.count})" else ""}")
            .setDescription(tooltip.drop(1).let {
                if (stack.hasCustomHoverName()) {
                    listOf(stack.item.description.string).plus(it)
                } else it
            }.joinToString("\n"))
            .let { builder: EmbedBuilder ->
                stack.rarity.color.color?.let { color -> builder.setColor(color) }
                builder
            }
            .build()
    }

    private fun formatHoverEntity(entity: HoverEvent.EntityTooltipInfo): MessageEmbed? {
        if (entity.type == EntityType.PLAYER) return null
        return EmbedBuilder()
            .setTitle(entity.name?.string)
            .setDescription(entity.type.description.string)
            .let { builder: EmbedBuilder ->
                val mobCategory = entity.type.category
                if (mobCategory.isFriendly) {
                    builder.setColor(Color.GREEN)
                }
                builder
            }
            .build()
    }

    /**
     * Sends a message into the configured Discord channel based on
     * the Chat [message] the [player] sent.
     * @param player the Player who sent the message
     * @param message the String contents of the message
     */
    fun sendChatToDiscord(player: ServerPlayer?, message: Component) {
        if (shuttingDown.get()) return

        val attachments = message.toFlatList().mapNotNull {
            it.style.hoverEvent
        }

        val formattedEmbeds: MutableList<MessageEmbed> = mutableListOf()
        val items: MutableList<ItemStack> = mutableListOf()

        for (attachment in attachments) {
            attachment.getValue(HoverEvent.Action.SHOW_TEXT)?.let {
                formattedEmbeds.add(formatHoverText(it))
            }
            attachment.getValue(HoverEvent.Action.SHOW_ITEM)?.itemStack?.let {
                formatHoverItems(it, items)?.let(formattedEmbeds::add)
            }
            attachment.getValue(HoverEvent.Action.SHOW_ENTITY)?.let {
                formatHoverEntity(it)?.let(formattedEmbeds::add)
            }
        }

        val escape = { it: String -> it.replace("_", "\\_") }
        textChannel?.sendMessage(
            if (player != null)
                AstralBotTextConfig.PLAYER_MESSAGE.get()
                    .replace("{{message}}", formatComponentToMarkdown(message))
                    .replace("{{fullName}}", escape(player.displayName.string))
                    .replace("{{name}}", escape(player.name.string))
            else escape(message.string)
        )
            ?.addEmbeds(formattedEmbeds)
            ?.setSuppressedNotifications(true)
            ?.queue()
    }

    /**
     * Sends a Discord message into the Minecraft chat as a System Message
     * @param message the Discord message that will be put into the Minecraft chat
     */
    private fun sendDiscordToChat(message: Message) {
        sendFormattedMessage(message) {
            server.playerList.broadcastSystemMessage(DiscordMessageComponent(it), false)
        }
    }

    /**
     * Event handler that gets fired when the bot receives a message
     * @param event the event which contains information about the message
     */
    override fun onMessageReceived(event: MessageReceivedEvent) {
        // Only send messages from the configured channel and only if the author isn't a bot
        if (event.channel.idLong == textChannel?.idLong && !event.author.isBot) {
            sendDiscordToChat(event.message)
        }
    }

    /**
     * Formats a Discord [Member] into a [Component] with their
     * name and their Discord role color.
     * @param member the member which will be used to get the data
     */
    private fun formattedUser(member: Member): MutableComponent {
        return Component.literal(member.effectiveName).withStyle {
            it
                .withColor(member.colorRaw)
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("@${member.user.name}")))
        }
    }

    /**
     * Formats a Discord [Message] into a [Component] that gets sent
     * into the Minecraft chat via the [send] argument
     *
     * This formatting entails the following:
     * - formatting users with [formattedUser]
     * - resolving replies
     * - the message itself
     * - if enabled in [AstralBotConfig], the embeds and attachments
     *   of the [message]
     *
     * @param message the relevant Discord message
     * @param send the function used to send the message into the Minecraft chat
     */
    private fun sendFormattedMessage(message: Message, send: (component: MutableComponent) -> Unit) {
        val comp = Component.empty()

        val messageContents = Component.empty()
        // This is the actual message content
        val actualMessage = Component.literal(message.contentDisplay)
        // If it's enabled in the config you can click on a message and get linked to said message
        // in the actual Discord client
        if (AstralBotConfig.CLICKABLE_MESSAGES.get()) {
            actualMessage.withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, message.jumpUrl)) }
        }
        messageContents.append(actualMessage)

        // Only adds embeds if it's enabled in the config
        if (AstralBotConfig.HANDLE_EMBEDS.get()) {
            messageContents.append(formatEmbeds(message))
        }

        val referencedAuthor = message.referencedMessage?.author?.id

        waitForSetup()

        if (referencedAuthor != null) {
            // This fetches the Member from the ID in a blocking manner
            guild?.retrieveMemberById(referencedAuthor)?.submit()?.whenComplete { repliedMember, error ->
                if (error != null) {
                    LOGGER.error("Failed to get member with id: $referencedAuthor", error)
                    return@whenComplete
                } else if (repliedMember == null) {
                    LOGGER.error("Failed to get member with id: $referencedAuthor")
                    return@whenComplete
                }
                message.member?.let {
                    comp.append(formatMessageWithUsers(messageContents, it, repliedMember))
                    send(comp)
                }
            }
        } else {
            message.member?.let {
                comp.append(formatMessageWithUsers(messageContents, it, null))
                send(comp)
            }
        }
    }

    private fun formatMessageWithUsers(message: MutableComponent, author: Member, replied: Member?): MutableComponent {
        val formatted = Component.empty()

        val templateSplitByUser = AstralBotTextConfig.DISCORD_MESSAGE.get().split("{{user}}")

        val formattedLiteral = { contents: String -> Component.literal(contents).withStyle(ChatFormatting.GRAY) }

        val withMessage = { templatePart: String ->
            val accumulator = Component.empty()

            val templateSplitMessage = templatePart.split("{{message}}")

            for ((index, value) in templateSplitMessage.withIndex()) {
                accumulator.append(formattedLiteral(value))

                if (index + 1 < templateSplitMessage.size) {
                    accumulator.append(message)
                }
            }

            accumulator
        }

        val withReply = { templatePart: String ->
            val accumulator = Component.empty()

            val reply = Component.empty()
            replied?.let {
                val replyTemplateSplit = AstralBotTextConfig.DISCORD_REPLY.get().split("{{replied}}")
                for ((index, value) in replyTemplateSplit.withIndex()) {
                    reply.append(formattedLiteral(value))

                    if (index + 1 < replyTemplateSplit.size) {
                        reply.append(formattedUser(it))
                    }
                }
            }

            val templateSplitByReply = templatePart.split("{{reply}}")

            for ((index, value) in templateSplitByReply.withIndex()) {
                accumulator.append(withMessage(value))

                if (index + 1 < templateSplitByReply.size) {
                    accumulator.append(reply.copy())
                }
            }
            accumulator
        }

        for ((index, value) in templateSplitByUser.withIndex()) {
            formatted.append(withReply(value))

            if (index + 1 < templateSplitByUser.size) {
                formatted.append(formattedUser(author))
            }
        }

        return formatted
    }

    /**
     * Formats the attachments and embeds on a Discord [Message] into
     * a comma separated list.
     * The following things can be en- or disabled in the [AstralBotConfig]:
     * - Making the embeds clickable
     * - Blocklist for disallowing certain URLs, blocking based on site hostname
     *
     * If an embed doesn't have a title it will be called embed`n` where `n` is
     * the index of the embed. All embeds come before attachments. If the URL of
     * an embed or attachment is blocked, it will display `BLOCKED` in red instead
     * of the name.
     *
     * @param message the message of which the attachments and embeds are handled
     */
    private fun formatEmbeds(message: Message): MutableComponent {
        val comp = Component.empty()
        // Adds a newline with space if there are embeds and the message isn't empty
        if (message.embeds.size + message.attachments.size + message.stickers.size > 0 && message.contentDisplay.isNotBlank()) comp.append(
            "\n "
        )
        var i = 0
        message.embeds.forEach {
            if (i++ != 0) comp.append(", ")
            comp.append(formatEmbed(it.title ?: "embed$i", it.url))
        }
        message.attachments.forEach {
            if (i != 0) {
                comp.append(", ")
            } else {
                i++
            }
            comp.append(formatEmbed(it.fileName, it.url))
        }
        message.stickers.forEach {
            if (i != 0) {
                comp.append(", ")
            } else {
                i++
            }
            comp.append(formatEmbed(it.name, it.icon.url))
        }
        return comp
    }

    /**
     * Formats a single attachment with a name and URL
     *
     * The following things can be en- or disabled in the [AstralBotConfig]:
     * - Making the embeds clickable
     * - Blocklist for disallowing certain URLs, blocking based on site hostname
     *
     * If the URL of an embed or attachment is blocked, it will display
     * `BLOCKED` in red instead of the name.
     *
     * @param name the name of the embed
     * @param url the url of the embed
     */
    private fun formatEmbed(name: String, url: String?): MutableComponent {
        val comp = Component.empty()
        if (AstralBotConfig.urlAllowed(url)) {
            val embedComponent = Component.literal(name)
            if (AstralBotConfig.CLICKABLE_EMBEDS.get()) {
                embedComponent.withStyle { style ->
                    if (url != null && AstralBotConfig.CLICKABLE_EMBEDS.get()) {
                        style.withColor(ChatFormatting.BLUE).withUnderlined(true)
                            .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    } else style
                }
            }
            comp.append(embedComponent)
        } else {
            comp.append(Component.literal("BLOCKED").withStyle(ChatFormatting.RED))
        }
        return comp
    }
}