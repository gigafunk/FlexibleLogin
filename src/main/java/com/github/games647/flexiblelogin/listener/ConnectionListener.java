/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 games647 and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.flexiblelogin.listener;

import com.github.games647.flexiblelogin.Account;
import com.github.games647.flexiblelogin.FlexibleLogin;
import com.github.games647.flexiblelogin.config.Config;
import com.github.games647.flexiblelogin.tasks.LoginMessageTask;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ClientConnectionEvent;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class ConnectionListener {

    private static final String VALID_USERNAME = "^\\w{2,16}$";

    private final FlexibleLogin plugin = FlexibleLogin.getInstance();

    @Listener
    public void onPlayerQuit(ClientConnectionEvent.Disconnect playerQuitEvent) {
        Player player = playerQuitEvent.getTargetEntity();
        Account account = plugin.getDatabase().remove(player);

        plugin.getProtectionManager().unprotect(player);

        if (account != null) {
            plugin.getAttempts().remove(player.getName());
            //account is loaded -> mark the player as logout as it could remain in the cache
            account.setLoggedIn(false);

            if (plugin.getConfigManager().getConfig().isUpdateLoginStatus()) {
                Sponge.getScheduler().createTaskBuilder()
                        .async().execute(() -> plugin.getDatabase().flushLoginStatus(account, false))
                        .submit(plugin);
            }
        }
    }

    @Listener
    public void onPlayerJoin(ClientConnectionEvent.Join playerJoinEvent) {
        Player player = playerJoinEvent.getTargetEntity();
        plugin.getProtectionManager().protect(player);

        Sponge.getScheduler().createTaskBuilder()
                .async()
                .execute(() -> onAccountLoaded(player))
                .submit(plugin);
    }

    @Listener
    public void onPlayerAuth(ClientConnectionEvent.Auth playerAuthEvent) {
        String playerName = playerAuthEvent.getProfile().getName().get();
        if (!playerName.matches(VALID_USERNAME)) {
            //validate invalid characters
            playerAuthEvent.setMessage(plugin.getConfigManager().getTextConfig().getInvalidUsername());
            playerAuthEvent.setCancelled(true);
        } else if (Sponge.getServer().getPlayer(playerName).isPresent()) {
            playerAuthEvent.setMessage(plugin.getConfigManager().getTextConfig().getAlreadyOnlineMessage());
            playerAuthEvent.setCancelled(true);
        }
    }

    public void onAccountLoaded(Player player) {
        Account loadedAccount = plugin.getDatabase().loadAccount(player);
        byte[] newIp = player.getConnection().getAddress().getAddress().getAddress();

        Config config = plugin.getConfigManager().getConfig();
        if (loadedAccount == null) {
            if (config.isCommandOnlyProtection()) {
                if (player.hasPermission(plugin.getContainer().getId() + ".registerRequired")) {
                    //command only protection but have to register
                    sendNotLoggedInMessage(player);
                }
            } else {
                //no account
                sendNotLoggedInMessage(player);
            }
        } else {
            long lastLogin = loadedAccount.getTimestamp().getTime();
            if (config.isIpAutoLogin() && Arrays.equals(loadedAccount.getIp(), newIp)
                    && lastLogin + 12 * 60 * 60 * 1000 < System.currentTimeMillis()
                    && !player.hasPermission(plugin.getContainer().getId() + ".no_auto_login")) {
                //user will be auto logged in
                player.sendMessage(plugin.getConfigManager().getTextConfig().getIpAutoLogin());
                loadedAccount.setLoggedIn(true);
            } else {
                //user has an account but isn't logged in
                sendNotLoggedInMessage(player);
            }
        }

        scheduleTimeoutTask(player);
    }

    private void sendNotLoggedInMessage(Player player) {
        //send the message if the player only needs to login
        if (!plugin.getConfigManager().getConfig().isBypassPermission()
                || !player.hasPermission(plugin.getContainer().getId() + ".bypass")) {
            Sponge.getScheduler().createTaskBuilder()
                    .execute(new LoginMessageTask(player))
                    .interval(2, TimeUnit.SECONDS).submit(plugin);
        }
    }

    private void scheduleTimeoutTask(Player player) {
        Config config = plugin.getConfigManager().getConfig();
        if (plugin.getConfigManager().getConfig().isBypassPermission()
                && player.hasPermission(plugin.getContainer().getId() + ".bypass")) {
            return;
        }

        if (!config.isCommandOnlyProtection() && config.getTimeoutLogin() != -1) {
            Sponge.getScheduler().createTaskBuilder()
                    .execute(() -> {
                        Account account = plugin.getDatabase().getAccountIfPresent(player);
                        if (account == null || !account.isLoggedIn()) {
                            player.kick(plugin.getConfigManager().getTextConfig().getTimeoutReason());
                        }
                    })
                    .delay(config.getTimeoutLogin(), TimeUnit.SECONDS)
                    .submit(plugin);
        }
    }
}
