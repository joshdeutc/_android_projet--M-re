package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Rules that close *part* of an app rather than the whole app, and the learning session that writes
 * them.
 *
 * The Instagram restriction this generalises was hand-written: I read view ids off the device over
 * adb, guessed which ones marked which screen, and got it wrong five times. None of those mistakes
 * needed a developer to fix — they needed someone who could see the screen. So the rules are learned
 * from the person using the phone instead: they open the screen to close, confirm, open the screen to
 * be sent back to, confirm, and [deriveMarkers] works out which ids distinguish the two.
 *
 * The differential is the part that matters. Presence alone is meaningless in modern apps — Instagram
 * keeps every tab's fragment alive in one pager, so `direct_inbox_action_bar` exists while you are
 * looking at the feed. Only ids that are **visible on the target and on none of the allowed screens**
 * can be trusted, and that is exactly the mistake a human eye does not catch but a set difference
 * does.
 *
 * Stored in its own file rather than in `limits.json`: threading a new section through the
 * `ConfigManager`/`DelayManager` parse/serialise/diff paths would be a bigger change, so removal
 * reuses the delay *values* from [DelayManager] while keeping the rules self-contained. Adding a rule
 * is a hardening and applies immediately; **removing one is gated by the personal and general delays**
 * on commercial flavors, and refused outright on `me` (see [PERMANENT_ON_THIS_FLAVOR] and
 * [requestRemoval]).
 */
object ScreenRuleManager {

    private const val TAG = "SelfControl.ScreenRule"
    private const val RULES_FILE = "screen_rules.json"

    /** Ids shared by every item of a Material `BottomNavigationView`, so never discriminating. */
    private val GENERIC_ID_SUFFIXES = listOf(
        "navigation_bar_item_icon_view",
        "navigation_bar_item_icon_container",
        "navigation_bar_item_labels_group",
        "navigation_bar_item_small_label_view",
        "navigation_bar_item_large_label_view",
        "navigation_bar_item_active_indicator_view"
    )

    /**
     * Words that name a *kind* of view rather than a screen. An id made only of these says nothing
     * about where the user is.
     *
     * This exists because the first WhatsApp rule learned `overlay` and `button_view` alongside the two
     * ids that actually meant something, `status_list` and `updates_list`. Both were genuinely visible
     * on the Updates tab and genuinely absent from Chats, so the set difference could not reject them —
     * they were simply views that happened to be on screen at capture time. A rule keyed on `overlay`
     * would fire wherever any overlay appears.
     */
    private val GENERIC_WORDS = setOf(
        "overlay", "button", "view", "container", "content", "root", "main", "pager", "holder",
        "icon", "title", "text", "image", "label", "divider", "progress", "toolbar", "bar",
        "layout", "list", "recycler", "item", "group", "stub", "frame", "wrapper", "panel",
        "coordinator", "host", "window", "size", "calculator", "background", "placeholder"
    )

    /**
     * How many words of an id carry screen-specific meaning — `updates_list` scores 1 ("updates"),
     * `button_view` scores 0. Zero means the id is pure furniture and must not become a marker.
     */
    private fun specificity(id: String): Int =
        id.substringAfter(":id/")
            .split('_')
            .count { it.isNotBlank() && it.lowercase() !in GENERIC_WORDS }

    // ──────────────────────────────────────
    //  Model
    // ──────────────────────────────────────

    /**
     * One learned restriction.
     *
     * [escapeTapId] plus [escapeTapIndex] describe the button that returns to the allowed screen.
     * The index is not redundant: WhatsApp's bottom bar is a Material `BottomNavigationView` whose
     * five items all carry the *same* view id, so "the third node with this id" is the only way to
     * name the tab the user chose. Instagram gives each tab its own id and the index is simply 0.
     */
    data class ScreenRule(
        val name: String,
        val packageName: String,
        val blockedIds: List<String>,
        val allowedIds: List<String>,
        val escapeTapId: String? = null,
        val escapeTapIndex: Int = 0,
        val escapeDeeplink: String? = null,
        /**
         * Per-rule removal delay, mirroring a curfew's `protection_delay_sec`. When higher than the
         * global delay it wins, and it outranks the settings unlock — a long-committed restriction
         * must not be defusable through a short unlock. Only relevant on non-`me` flavors; on `me`
         * removal is refused outright regardless of this value.
         */
        val protectionDelaySec: Int? = null,
        val blockedHours: String? = null,
        /** Counters kept by the enforcement side, so a rule that quietly stopped working is visible. */
        val successes: Int = 0,
        val failures: Int = 0
    )

    /**
     * Whether, on this build, a screen rule can never be removed once created.
     *
     * True only for the `me` flavor (LEVEL 3). It is a compile-time constant, not a setting, on
     * purpose: a switch that exists is a switch temptation goes looking for — the same reasoning
     * that removed the tolerance window from the watchdog rather than zeroing it. On every other
     * (commercial) flavor removal is allowed but gated by the personal and general delays.
     */
    val PERMANENT_ON_THIS_FLAVOR: Boolean = BuildConfig.LEVEL == 3

    /** Outcome of a removal request, so the UI can explain what happened. */
    sealed class RemovalOutcome {
        /** `me` flavor: rules are permanent, nothing was queued. */
        object Permanent : RemovalOutcome()
        /** No delay applied (or settings unlocked): the rule is already gone. */
        object Immediate : RemovalOutcome()
        /** Queued; [seconds] to wait. [fromRuleTimer] true when the rule's own timer won over global. */
        data class Deferred(val seconds: Int, val fromRuleTimer: Boolean) : RemovalOutcome()
        /** A removal for this rule was already queued; [remainingMs] until it fires. */
        data class AlreadyPending(val remainingMs: Long) : RemovalOutcome()
        object NotFound : RemovalOutcome()
    }

    // ──────────────────────────────────────
    //  Marker derivation
    // ──────────────────────────────────────

    /**
     * Choose the ids that identify [targetVisible] and appear on none of [allowedVisibleSets].
     *
     * Only *visible* ids should be passed in — the caller captures them with a visibility filter,
     * because an id present but off-screen says nothing about which screen is up.
     *
     * Returns an empty list when the screens cannot be told apart, which is a real outcome and must
     * be reported to the user rather than papered over: two tabs of the same app sometimes share
     * every id, and a rule built on that would fire on both.
     */
    fun deriveMarkers(
        targetVisible: Set<String>,
        allowedVisibleSets: List<Set<String>>
    ): List<String> {
        val onAllowed = allowedVisibleSets.flatten().toSet()
        return targetVisible
            .asSequence()
            .filter { id -> id !in onAllowed }
            .filter { id -> GENERIC_ID_SUFFIXES.none { id.endsWith(it) } }
            // Reject furniture outright: an id whose every word names a kind of view was on screen by
            // coincidence, not because of where the user was.
            .filter { specificity(it) > 0 }
            // Most meaningful first, so the strongest marker leads the rule.
            .sortedByDescending { specificity(it) }
            .toList()
    }

    // ──────────────────────────────────────
    //  Persistence
    // ──────────────────────────────────────

    /** The whole file as a JSON object, or an empty one — the single reader both keys go through. */
    private fun readRoot(ctx: Context): JSONObject {
        val file = File(ctx.filesDir, RULES_FILE)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read $RULES_FILE: ${e.message}")
            JSONObject()
        }
    }

    private fun writeRoot(ctx: Context, root: JSONObject): Boolean = try {
        File(ctx.filesDir, RULES_FILE).writeText(root.toString(2))
        true
    } catch (e: Exception) {
        Log.e(TAG, "Cannot write $RULES_FILE: ${e.message}")
        false
    }

    fun load(ctx: Context): List<ScreenRule> {
        val arr = readRoot(ctx).optJSONArray("rules") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> parseRule(arr.getJSONObject(i)) }
    }

    /**
     * Persist [rules], **preserving** the pending-removals list that shares this file. Rewriting the
     * whole object with only "rules" — as the first version did — would silently drop any queued
     * removal, so both keys are always carried through [readRoot]/[writeRoot].
     */
    fun save(ctx: Context, rules: List<ScreenRule>): Boolean {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject().apply {
                    put("name", r.name)
                    put("package", r.packageName)
                    put("blocked_ids", JSONArray(r.blockedIds))
                    put("allowed_ids", JSONArray(r.allowedIds))
                    r.escapeTapId?.let { put("escape_tap_id", it) }
                    put("escape_tap_index", r.escapeTapIndex)
                    r.escapeDeeplink?.let { put("escape_deeplink", it) }
                    r.protectionDelaySec?.let { put("protection_delay_sec", it) }
                    r.blockedHours?.let { put("blocked_hours", it) }
                    put("successes", r.successes)
                    put("failures", r.failures)
                }
            )
        }
        val root = readRoot(ctx).put("rules", arr)
        return writeRoot(ctx, root)
    }

    /** Add or replace by [ScreenRule.name]. A new block is a hardening, so it applies at once. */
    fun upsert(ctx: Context, rule: ScreenRule, allowOverwrite: Boolean = false): Boolean {
        val currentRules = load(ctx)
        val ruleToSave = if (!allowOverwrite && currentRules.any { it.name.equals(rule.name, ignoreCase = true) }) {
            var suffix = 2
            var uniqueName = "${rule.name} ($suffix)"
            while (currentRules.any { it.name.equals(uniqueName, ignoreCase = true) }) {
                suffix++
                uniqueName = "${rule.name} ($suffix)"
            }
            rule.copy(name = uniqueName)
        } else {
            rule
        }
        val others = currentRules.filterNot { it.name.equals(ruleToSave.name, ignoreCase = true) }
        val ok = save(ctx, others + ruleToSave)
        if (ok) {
            Log.w(TAG, "Rule saved: ${ruleToSave.name} (${ruleToSave.blockedIds.size} marker(s))")
            EventLog.log(
                ctx, "SCREEN_RULE",
                "saved '${ruleToSave.name}' pkg=${ruleToSave.packageName} markers=${ruleToSave.blockedIds.size}"
            )
        }
        return ok
    }

    /** Record whether a redirect actually reached the allowed screen, for rule-health reporting. */
    fun recordOutcome(ctx: Context, name: String, reached: Boolean) {
        val rules = load(ctx)
        val rule = rules.firstOrNull { it.name == name } ?: return
        val updated = if (reached) {
            rule.copy(successes = rule.successes + 1)
        } else {
            rule.copy(failures = rule.failures + 1)
        }
        save(ctx, rules.map { if (it.name == name) updated else it })
    }

    // ──────────────────────────────────────
    //  Removal — gated by delay, or refused on `me`
    // ──────────────────────────────────────

    private var lastRemovalSweep = 0L

    /**
     * Ask to remove the rule named [name].
     *
     * On `me` this always returns [RemovalOutcome.Permanent] and changes nothing — the point of that
     * flavor is that a restriction, once set, cannot be walked back. On every other flavor removal is
     * allowed but passes the same gate as a curfew relaxation: it waits for the larger of the rule's
     * own `protectionDelaySec` and the current global effective delay, and a rule-timer delay outranks
     * the settings unlock exactly as `ConfigManager.requiredDefer` decides for curfews. The rule keeps
     * enforcing throughout the wait, and the queued removal can be cancelled (a hardening) until it
     * fires.
     */
    fun requestRemoval(ctx: Context, name: String): RemovalOutcome {
        val rule = load(ctx).firstOrNull { it.name == name } ?: return RemovalOutcome.NotFound

        if (PERMANENT_ON_THIS_FLAVOR) {
            Log.w(TAG, "Removal refused (permanent flavor): ${rule.name}")
            EventLog.log(ctx, "SCREEN_RULE", "removal refused (permanent): ${rule.name}")
            return RemovalOutcome.Permanent
        }

        pendingRemovalRemainingMs(ctx, name)?.let { return RemovalOutcome.AlreadyPending(it) }

        val perRule = rule.protectionDelaySec ?: 0
        val global = DelayManager.getCurrentEffectiveDelaySeconds(ctx)
        val waitSec = max(perRule, global)
        val fromRuleTimer = perRule > 0 && perRule >= global
        val unlockApplies = !fromRuleTimer && DelayManager.isSettingsUnlocked(ctx)

        if (waitSec == 0 || unlockApplies) {
            removeNow(ctx, name)
            return RemovalOutcome.Immediate
        }

        val executeAt = System.currentTimeMillis() + waitSec * 1000L
        val pending = loadPending(ctx).filterNot { it.first == name } + (name to executeAt)
        savePending(ctx, pending)
        Log.w(TAG, "Removal queued for $name in ${waitSec}s (ruleTimer=$fromRuleTimer)")
        EventLog.log(ctx, "SCREEN_RULE", "removal queued '${rule.name}' in ${waitSec}s")
        return RemovalOutcome.Deferred(waitSec, fromRuleTimer)
    }

    /** Cancel a queued removal — a hardening (the block stays), so always allowed and immediate. */
    fun cancelRemoval(ctx: Context, name: String): Boolean {
        val pending = loadPending(ctx)
        if (pending.none { it.first == name }) return false
        savePending(ctx, pending.filterNot { it.first == name })
        Log.w(TAG, "Removal cancelled for $name")
        EventLog.log(ctx, "SCREEN_RULE", "removal cancelled '$name'")
        return true
    }

    /** Millis left before the queued removal of [name] fires, or null if none is queued. */
    fun pendingRemovalRemainingMs(ctx: Context, name: String): Long? {
        val at = loadPending(ctx).firstOrNull { it.first == name }?.second ?: return null
        val left = at - System.currentTimeMillis()
        return if (left > 0) left else 0L
    }

    /**
     * Apply every queued removal whose timer has elapsed. Called from the service tick, so it is
     * throttled to a few seconds and is a no-op on the permanent flavor.
     */
    fun applyPendingRemovalsIfReady(ctx: Context) {
        if (PERMANENT_ON_THIS_FLAVOR) return
        val now = System.currentTimeMillis()
        if (now - lastRemovalSweep < 5_000L) return
        lastRemovalSweep = now

        val pending = loadPending(ctx)
        if (pending.isEmpty()) return
        val due = pending.filter { it.second <= now }
        if (due.isEmpty()) return
        for ((name, _) in due) removeNow(ctx, name)
        savePending(ctx, pending.filterNot { p -> due.any { it.first == p.first } })
    }

    /**
     * Remove a rule unconditionally — the developer escape hatch, reached only over adb (the
     * `REMOVE_SCREEN_RULE` command lives in the deviceAdmin/me manifest, which are the debuggable
     * flavors). This is deliberately outside the flavor gate: on `me` the phone UI treats a rule as
     * permanent, but someone with a PC and adb can still lift it. That matches the threat model —
     * close the weak-moment breach, not the deliberate technical one — and it is the clean path so a
     * bad rule never has to be fixed by hand-editing the JSON file (which has vided a config before).
     * Returns true if a rule was actually removed.
     */
    fun removeImmediate(ctx: Context, name: String): Boolean {
        if (load(ctx).none { it.name == name }) return false
        cancelRemoval(ctx, name)
        removeNow(ctx, name)
        return true
    }

    private fun removeNow(ctx: Context, name: String) {
        val remaining = load(ctx).filterNot { it.name == name }
        save(ctx, remaining)
        Log.w(TAG, "Rule removed: $name")
        EventLog.log(ctx, "SCREEN_RULE", "removed '$name'")
    }

    private fun loadPending(ctx: Context): List<Pair<String, Long>> {
        val arr = readRoot(ctx).optJSONArray("pending_removals") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optString("name").ifBlank { return@mapNotNull null }
            n to o.optLong("execute_at", 0L)
        }
    }

    private fun savePending(ctx: Context, pending: List<Pair<String, Long>>) {
        val arr = JSONArray()
        for ((n, at) in pending) {
            arr.put(JSONObject().put("name", n).put("execute_at", at))
        }
        writeRoot(ctx, readRoot(ctx).put("pending_removals", arr))
    }

    private fun parseRule(o: JSONObject): ScreenRule? = try {
        val blocked = o.optJSONArray("blocked_ids")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        } ?: emptyList()
        if (blocked.isEmpty()) {
            Log.w(TAG, "Ignoring rule '${o.optString("name")}' — no blocked ids")
            null
        } else {
            ScreenRule(
                name = o.getString("name"),
                packageName = o.getString("package"),
                blockedIds = blocked,
                allowedIds = o.optJSONArray("allowed_ids")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                escapeTapId = o.optString("escape_tap_id").ifBlank { null },
                escapeTapIndex = o.optInt("escape_tap_index", 0),
                escapeDeeplink = o.optString("escape_deeplink").ifBlank { null },
                protectionDelaySec = if (o.has("protection_delay_sec")) o.getInt("protection_delay_sec") else null,
                blockedHours = if (o.has("blocked_hours")) o.getString("blocked_hours").ifBlank { null } else null,
                successes = o.optInt("successes", 0),
                failures = o.optInt("failures", 0)
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Malformed rule skipped: ${e.message}")
        null
    }

    /**
     * Update the per-rule protection delay on non-`me` flavors.
     * On `me`, rules are strictly permanent (ADB only).
     */
    fun updateProtectionDelay(ctx: Context, name: String, newDelaySec: Int?): Boolean {
        if (PERMANENT_ON_THIS_FLAVOR) return false
        val rules = load(ctx)
        val rule = rules.firstOrNull { it.name == name } ?: return false
        val updated = rule.copy(protectionDelaySec = newDelaySec)
        return save(ctx, rules.map { if (it.name == name) updated else it })
    }
}
