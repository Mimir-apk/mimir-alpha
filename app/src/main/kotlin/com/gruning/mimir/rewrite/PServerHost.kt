package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Privileged command execution through the handheld's own **PServer** service.
 *
 * **Why this exists, stated plainly.** Mimir's Tweaker integration reaches the hardware through a
 * SECOND INSTALLED APP (`com.gruning.mimir.tweaker`), bound over an exported service, guarded by a
 * `signature`-protected permission, admitted by a package-contract classifier
 * ([classifyTweakerPair]), and then narrowed again by per-device capability manifests and
 * certified-profile sets. On an AYN Thor test device, 2026-08-31, that chain produced `UNAVAILABLE` and
 * always would have: Mimir's debug build is signed `5c0158bb`, the installed Tweaker `31a57625`, and
 * `com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER` is `prot=signature`. Two different keys mean
 * the permission can never be held, the pair can never be `ELIGIBLE`, capabilities stay null, and
 * every surface downstream reads UNAVAILABLE for ever. No amount of granting, rebooting or
 * reinstalling could have fixed it — and the failure was indistinguishable, from the outside, from
 * "this hardware is not supported".
 *
 * **This path has no pairing, no manifest, no allow-list and no certification.** It asks the operating
 * system one question — is there a `PServerBinder` — and the kernel another — does this SoC publish
 * frequency tables. Both are FACTS about the device in your hand, discovered at runtime. Neither can
 * be wrong about a handheld nobody has written a manifest for yet, which is the exact failure the
 * qualification model kept producing.
 *
 * **Attribution.** The technique — obtaining `PServerBinder` from `ServiceManager` by reflection and
 * driving it with a transaction carrying a shell command — is documented by **ClusterTune**
 * (AurelioB), which credits **O2P Tweaks** (FeralAI) for it, and is also used by **PULSE**
 * (keiretrogaming). **No line of their source is copied here**: what is reused is the interface
 * fact — a service name, a transaction code, an argument shape — which is how you call an API, not an
 * author's expression of one. Also recorded in the root `NOTICE` (section 7), because attribution
 * that lives only in a source comment is attribution nobody reads.
 *
 * **What this can do is what a shell can do, as the PServer host user.** That is a real privilege.
 * Every command must be built by code in this tree out of values this tree validated — never from a
 * string that arrived from outside the process.
 */
/** Where [PServerHost]'s device self-test leaves its marker. adb-readable, and nothing else reads it. */
const val PServerProbeMarkerPath = "/data/local/tmp/mimir_pserver_probe"

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
object PServerHost {

    private const val TAG = "MimirPServer"

    /** The service name published by the vendor's performance server. */
    private const val ServiceName = "PServerBinder"

    /**
     * The host-launch transaction.
     *
     * The reply parcel carries nothing worth reading, and a `false` return means the transaction was
     * not DELIVERED — not that the command failed. **A successful transact is therefore not proof the
     * command did anything**, which is why every caller in this tree verifies by reading back the node
     * it asked to change. Claiming otherwise would be the same class of lie as a progress bar drawn
     * from a total nobody knows.
     */
    private const val LaunchTransaction = 0

    @Volatile
    private var cached: IBinder? = null

    /** True when this device publishes a PServer we can actually talk to right now. */
    fun available(): Boolean = binder() != null

    /** Run [command] as the PServer host. Returns whether the TRANSACTION was accepted. */
    fun run(command: String): Boolean {
        val binder = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, "0"))
            binder.transact(LaunchTransaction, data, reply, 0)
        } catch (throwable: Throwable) {
            if (!binder.isBinderAlive) cached = null
            Log.w(TAG, "pserver transact failed", throwable)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun binder(): IBinder? {
        cached?.takeIf(IBinder::isBinderAlive)?.let { return it }
        return synchronized(this) {
            cached?.takeIf(IBinder::isBinderAlive) ?: lookUp().also { cached = it }
        }
    }

    private fun lookUp(): IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(serviceManager, ServiceName) as? IBinder
    }.getOrElse {
        Log.w(TAG, "no $ServiceName on this device", it)
        null
    }
}
