package com.offlinevoicerelay.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_STATE
import android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_ENABLED

/**
 * Bridges Android's WifiP2pManager broadcast events to [WifiDirectTransport].
 *
 * Deliberately registered DYNAMICALLY (via [intentFilter] + Context.registerReceiver
 * from the owning service), not declared in AndroidManifest.xml: since Android 8.0,
 * most implicit broadcasts sent to manifest-declared receivers are dropped unless the
 * action is on Android's background-broadcast exemption list, and WiFi P2P actions
 * are not reliably on that list across OEM builds. A receiver registered while the
 * foreground service is alive does not have this restriction.
 */
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val transport: WifiDirectTransport
) : BroadcastReceiver() {

    val intentFilter: IntentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val enabled = intent.getIntExtra(EXTRA_WIFI_STATE, -1) == WIFI_P2P_STATE_ENABLED
                if (!enabled) transport.stop()
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
                    transport.onPeersAvailable(peers.deviceList)
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                manager.requestConnectionInfo(channel) { info ->
                    if (info != null && info.groupFormed) {
                        transport.onGroupInfoAvailable(
                            isGroupOwner = info.isGroupOwner,
                            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                        )
                    } else {
                        transport.onGroupDismantled()
                    }
                }
            }
        }
    }
}
