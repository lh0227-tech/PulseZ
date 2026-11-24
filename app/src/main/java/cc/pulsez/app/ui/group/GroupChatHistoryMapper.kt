package cc.pulsez.app.ui.group

import cc.pulsez.app.core.GroupMember
import cc.pulsez.app.core.MentionParser
import cc.pulsez.app.data.db.MessageEntity

/**
 * Maps a chronological group-history window and its compact prefix context to the same UI messages
 * used by [GroupChatViewModel]. Keeping this pure lets Room paging tests exercise the real boundary
 * mapping without constructing the app container.
 */
internal fun mapGroupHistoryToUiMessages(
    history: List<MessageEntity>,
    members: List<GroupMember>,
    speakerNames: Map<String, String>,
    precedingUser: MessageEntity? = null,
    precedingAssistantSeen: Boolean = false,
): List<GroupMessageUi> {
    var mainSeen = precedingAssistantSeen
    // Mentions belong to the turn even when its user row is just before the current page.
    var addressedIds: Set<String> = precedingUser?.let { user ->
        MentionParser.parse(user.content, members).addressedIds.toSet()
    }.orEmpty()
    return history.mapNotNull { message ->
        when (message.role) {
            "user" -> {
                mainSeen = false
                addressedIds = MentionParser.parse(message.content, members).addressedIds.toSet()
                GroupMessageUi(id = message.id, role = GroupMessageRole.USER, text = message.content)
            }

            "assistant" -> {
                val isMain = !mainSeen
                val kind = if (isMain) GroupMessageKind.MAIN else GroupMessageKind.REACTION
                mainSeen = true
                GroupMessageUi(
                    id = message.id,
                    role = GroupMessageRole.MEMBER,
                    text = message.content,
                    speakerId = message.speakerCharacterId,
                    speakerName = groupSpeakerDisplayName(message.speakerCharacterId?.let(speakerNames::get)),
                    kind = kind,
                    // A named member's main and second addressed reply both retain the @ marker.
                    addressed = message.speakerCharacterId != null &&
                        isAddressedReply(message.speakerCharacterId, addressedIds),
                )
            }

            else -> null
        }
    }
}
