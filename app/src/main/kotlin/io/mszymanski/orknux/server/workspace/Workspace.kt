package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table

@Entity
@Table(name = "workspace")
class Workspace(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    /**
     * The roles that open this workspace. Empty means administrators only.
     *
     * Roles rather than the name of a directory group: the group was the identity
     * provider's vocabulary in this application's model, it only ever made sense
     * for LDAP, and two workspaces meaning the same audience had no way to say so.
     *
     * Eagerly fetched because every access check needs them, and the set is small
     * by construction — a workspace has an audience, not a directory.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "workspace_role",
        joinColumns = [JoinColumn(name = "workspace_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf(),

    /**
     * The roles that also *administer* this workspace. Empty means installation
     * administrators only, which is what every workspace had before this existed.
     *
     * Meant to be a subset of [roles]: administering a workspace one cannot see is
     * nothing, so `WorkspaceAPI.updateWorkspace` refuses a set that is not, and
     * `WorkspaceAccess.canSee` counts these too in case a database was edited by
     * hand.
     *
     * Per workspace, which is the whole of the idea — one role can lead the support
     * workspace and merely work in the backend one. Eagerly fetched for the same
     * reason [roles] is: the access check needs them on every call, and the set is
     * smaller still.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "workspace_admin_role",
        joinColumns = [JoinColumn(name = "workspace_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var adminRoles: MutableSet<Role> = mutableSetOf(),

    /**
     * The model used for the small jobs nobody asks for — naming a chat from
     * what was said, first among them.
     *
     * Set for the workspace rather than per chat: it is not the conversation
     * anyone is having, and a cheap model is the right one for it even where
     * the chat uses an expensive one. Null means those jobs do not happen.
     */
    @Column(name = "companion_model_id")
    var companionModelId: Long? = null,

    /**
     * The model that turns speech into text, for the microphone in a chat.
     *
     * A workspace setting because it is about this installation's hardware —
     * where Whisper is running — rather than about any one conversation. Null
     * means the microphone is not offered: better than a button that fails.
     */
    @Column(name = "transcription_model_id")
    var transcriptionModelId: Long? = null,

    /**
     * The model that reads an answer aloud, for the speaker under one.
     *
     * The mirror of [transcriptionModelId], and a workspace setting for the same
     * reason. Null means no speaker is offered, which is better than one that
     * fails on every answer.
     */
    @Column(name = "speech_model_id")
    var speechModelId: Long? = null,

    /**
     * The model behind the quick chat, the panel that opens beside the page.
     *
     * Kept apart from the companion model because the jobs are not the same one:
     * naming a chat is a single cheap completion, while this answers questions
     * about the installation and calls orknux's own tools to do it. Null means
     * the button is not offered.
     */
    @Column(name = "quick_chat_model_id")
    var quickChatModelId: Long? = null,

    /**
     * Whether the quick chat may start things, or only look them up.
     *
     * Off by default, which is not the same as off by nature: the panel opens
     * over whatever somebody is reading, and a model that decides "run it" from
     * a question is a worse mistake there than on a page with a button on it.
     * A workspace that wants it can say so.
     */
    @Column(name = "quick_chat_may_write", nullable = false)
    var quickChatMayWrite: Boolean = false,

    /**
     * What an agent that sets no share of its own is given, as a percentage of
     * its model's context window.
     *
     * The middle step of three: an agent's own share, then this, then the
     * built-in allowance. Null here is what every workspace has until somebody
     * decides otherwise, and a workspace that leaves it null behaves exactly as
     * it did before this column existed.
     *
     * It exists because the per-agent setting is the right place to make an
     * exception and the wrong place to state a policy. An installation that has
     * decided its agents should remember twice as much as the built-in
     * allowance had to say so once per agent and again on every agent created
     * afterwards; this is that decision written down once, in the place the
     * agents already belong to.
     *
     * A percentage rather than a count of tokens for the same reason the
     * agent's is - see `SessionMemoryBudget` - and doubly so here, because a
     * workspace runs several models whose windows differ by an order of
     * magnitude and a share is the only unit that travels between them.
     *
     * Which is also why nothing but the bounds is checked when this is saved.
     * The narrower refusals - a window too small to carry an exchange, a model
     * that reserves most of its window for its answer - need one model, and
     * this default is not tied to one. They still apply, at the place the
     * budget is actually worked out, against the model the agent in question
     * really uses.
     */
    @Column(name = "default_memory_share")
    var defaultMemoryShare: Int? = null,

    /**
     * How long a pause has to run, after somebody has been talking, before
     * voice mode ends their turn and sends what they said.
     *
     * The setting that actually ends a turn, and the one to move when somebody
     * is cut off. It is a judgement about how people talk rather than a fact
     * about audio: people stop mid-sentence to think, and a pause shorter than
     * an ordinary one of those reads every stop as "your go".
     *
     * Null is what every workspace starts as and means the workspace has
     * decided nothing, so voice mode uses its own pause. The number is
     * deliberately not written down here as well - it belongs to the interface,
     * which is the only place that can judge it, and a copy on this side would
     * be a second source of truth that drifts the first time one of them
     * changes.
     */
    @Column(name = "voice_pause_ends_turn_ms")
    var voicePauseEndsTurnMs: Int? = null,

    /**
     * How far above the room's own noise a sound has to stand to count as a
     * voice, as a percentage - 300 is three times the room.
     *
     * A ratio rather than a loudness, because speech is several times the level
     * of the room it is spoken in whatever that room is, so this travels
     * between microphones in a way a fixed level does not. Lower is more
     * sensitive, and it is what to lower for somebody who talks quietly or sits
     * away from the microphone.
     *
     * There is a fixed level in the interface as well, OR'd with this one, and
     * it is not exposed here on purpose. The two ask the same question twice;
     * the fixed one exists only so that a silent room is not absurdly
     * sensitive - where the room is next to nothing, three times nothing is
     * still nothing and every breath clears the ratio. It is a guard against
     * this setting's failure mode rather than a second knob to turn, and
     * offering it as one would invite somebody to defeat the guard.
     *
     * Null means the workspace has decided nothing.
     */
    @Column(name = "voice_speech_over_room_percent")
    var voiceSpeechOverRoomPercent: Int? = null,

    /**
     * How long an open microphone stays open when nothing else has ended the
     * turn.
     *
     * A fuse, not a limit on how much anybody may say. The pause above is what
     * ends a turn; this only fires when no pause ever came, which means a
     * microphone left open in an empty room or a room noisy enough to read as
     * somebody talking. Every value this has held that looked like a reasonable
     * limit on a turn turned out to cut somebody off in the middle of a
     * sentence, which is why the bound on it is where it is.
     *
     * Null means the workspace has decided nothing.
     */
    @Column(name = "voice_unattended_microphone_ms")
    var voiceUnattendedMicrophoneMs: Int? = null,

    /**
     * Where an answer is cut before it is handed to the speech model.
     *
     * A value rather than a null, unlike the three above. Those store a
     * departure from a number the interface owns, and null is how a workspace
     * says it has decided nothing; this stores one of three named things a
     * listener can ask for, and [SpeechChunking.SENTENCE] is one of them by
     * name. "The default" as a fourth choice would be a second spelling of a
     * choice already on the list, and a form offering both would have to say
     * which of the two it saved.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "voice_speech_chunking", nullable = false, length = 16)
    var voiceSpeechChunking: SpeechChunking = SpeechChunking.SENTENCE,
)

/**
 * Where an answer being read aloud is cut for the speech provider.
 *
 * Reading is pipelined: a piece is asked for, played, and the next is made
 * while it is in the air, which is what stops the wait before the first word
 * being the wait for the last one to be synthesised. Where the cuts fall is the
 * trade this names, and it has no right answer - it is a listening preference,
 * which is why a workspace states it.
 *
 * The 220-character ceiling that holds a sentence-cut piece to about a breath
 * lives in the interface and is deliberately not offered here. A mode and a
 * size is two knobs describing one thing, and the second is only ever wrong in
 * ways the first already covers.
 */
enum class SpeechChunking {
    /**
     * No cutting at all: one request for the finished answer.
     *
     * Nothing is asked for until the answer is complete, so the silence before
     * the first word is however long the whole thing takes to synthesise - and
     * the longer the answer, the longer the wait. What it buys is one seam-free
     * clip from one request, which is what somebody reading a short answer on a
     * metered provider wants.
     */
    NONE,

    /**
     * Whole sentences, gathered up to about a breath each.
     *
     * The default, and what a hands-free conversation needs: the first sentence
     * is in the air while the second is being made, so somebody hears an answer
     * begin at roughly the speed a person would begin one.
     */
    SENTENCE,

    /**
     * Paragraph boundaries, and nothing shorter.
     *
     * Fewer and longer requests than [SENTENCE], so fewer joins between clips
     * to hear and less pressure on the provider, at the cost of a later first
     * word. The middle of the three, and the one to move to when the seams
     * between sentences are what is noticeable.
     */
    PARAGRAPH,
}
