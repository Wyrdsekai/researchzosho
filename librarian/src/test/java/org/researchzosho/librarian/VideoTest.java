package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The video helper: the tunnel's settings from a WARP profile, the docker command lines, the yt-dlp command, and what counts as a refusal — without docker or the network. */
class VideoTest {

    final List<List<String>> ran = new ArrayList<>();
    final Video.Runner real = Video.runner;

    @AfterEach void restore() { Video.runner = real; }

    @Test
    void theTunnelsSettingsComeFromTheProfileWgcfWrites() {
        String env = Video.envFromProfile("""
                [Interface]
                PrivateKey = PRIVATEKEYBASE64=
                Address = 172.16.0.2/32, 2606:4700:110:8f0a::2/128
                DNS = 1.1.1.1
                MTU = 1280

                [Peer]
                PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = engage.cloudflareclient.com:2408
                """, host -> host.equals("engage.cloudflareclient.com") ? "162.159.193.5" : null);
        assertTrue(env.contains("VPN_SERVICE_PROVIDER=custom\nVPN_TYPE=wireguard\n"), env);
        assertTrue(env.contains("WIREGUARD_PRIVATE_KEY=PRIVATEKEYBASE64=\n"), env);
        assertTrue(env.contains("WIREGUARD_PUBLIC_KEY=bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=\n"), env);
        assertTrue(env.contains("WIREGUARD_ADDRESSES=172.16.0.2/32\n"), "the IPv4 address alone: " + env);
        assertTrue(env.contains("WIREGUARD_ENDPOINT_IP=162.159.193.5\nWIREGUARD_ENDPOINT_PORT=2408\n"), "the name is resolved to an address, which is what gluetun's custom WireGuard takes: " + env);
        String unresolved = Video.envFromProfile("[Peer]\nEndpoint = engage.cloudflareclient.com:2408\n", host -> null);
        assertTrue(unresolved.contains("WIREGUARD_ENDPOINT_IP=" + Video.WARP_ENDPOINT_IP + "\n"), "and WARP's known address stands in when the name cannot be resolved: " + unresolved);
        assertTrue(env.contains("WIREGUARD_MTU=1280\n") && env.contains("HTTPPROXY=on\nHTTPPROXY_LISTENING_ADDRESS=:18888\n") && env.contains("FIREWALL_INPUT_PORTS=4416\n"), env);
        String byIp = Video.envFromProfile("[Peer]\nEndpoint = 162.159.192.1:2408\n");
        assertTrue(byIp.contains("WIREGUARD_ENDPOINT_IP=162.159.192.1\n"), "an address given as a number goes in as one: " + byIp);
    }

    @Test
    void theDockerCommandsMakeTheTunnelAndPutTheProviderInItsNetwork() {
        List<List<String>> fresh = Video.plan("absent", "absent");
        assertEquals(2, fresh.size());
        String vpn = String.join(" ", fresh.get(0)), pot = String.join(" ", fresh.get(1));
        assertTrue(vpn.startsWith("docker run -d --name researchzosho-warp --restart unless-stopped --cap-add NET_ADMIN --device /dev/net/tun:/dev/net/tun"), vpn);
        assertTrue(vpn.contains("-p 127.0.0.1:18888:18888 -p 127.0.0.1:14416:4416") && vpn.contains("--env-file ") && vpn.endsWith(Video.VPN_IMAGE), "the proxy and the provider's port on this machine only: " + vpn);
        assertTrue(pot.contains("--network container:researchzosho-warp " + Video.POT_IMAGE + " --host 0.0.0.0"), pot);
        assertEquals(List.of(List.of("docker", "start", "researchzosho-warp"), List.of("docker", "start", "researchzosho-pot")), Video.plan("stopped", "stopped"));
        assertTrue(Video.plan("running", "running").isEmpty());
        List<List<String>> tunnelRemade = Video.plan("absent", "stopped");
        assertEquals(List.of("docker", "rm", "-f", "researchzosho-pot"), tunnelRemade.get(1), "a provider from an earlier tunnel is made anew: " + tunnelRemade);
    }

    @Test
    void everyYtDlpCallGoesThroughTheTunnelWithTheTokenProvider() {
        String cmd = String.join(" ", Video.command());
        assertTrue(cmd.contains("--ignore-config"), "none of the person's own yt-dlp settings: " + cmd);
        assertTrue(cmd.contains("--proxy http://127.0.0.1:18888"), cmd);
        assertTrue(cmd.contains("--extractor-args youtubepot-bgutilhttp:base_url=http://127.0.0.1:14416"), cmd);
    }

    @Test
    void whatYouTubeSaysWhenItRefusesThisRoute() {
        assertTrue(Video.refused("ERROR: [youtube] abc: Sign in to confirm you're not a bot. This helps protect our community."));
        assertTrue(Video.refused("ERROR: unable to download webpage: HTTP Error 429: Too Many Requests"));
        assertFalse(Video.refused("[download] 100% of 3.2MiB"));
        assertFalse(Video.refused(null));
    }

    @Test
    void withoutDockerInstallAndStartSaySoAndStopThere() {
        Video.runner = (cmd, t) -> { ran.add(cmd); throw new IOException("docker: not found"); };
        assertTrue(Video.install().startsWith("!docker is not on this machine"));
        assertTrue(Video.start().startsWith("!docker is not on this machine"));
        assertEquals(2, ran.size(), "only the version probes ran: " + ran);
        assertEquals("absent", Video.state(Video.CONTAINER_VPN));
    }

    @Test
    void aTestSearchListsWhatYouTubeAnswered() {
        // the helper is not installed in a test's home, so the refusal to run unproxied is what comes back
        Video.Result r = Video.ytdlp(List.of("ytsearch1:anything"), Duration.ofSeconds(5));
        assertEquals(3, r.code());
        assertTrue(r.out().contains("not installed") || r.out().contains("not running"), "nothing runs without the tunnel and the provider: " + r.out());
    }

    @Test
    void thePaceIsKeptAcrossProcessesThroughAFileInTheHelpersFolder() throws Exception {
        Path home = Files.createTempDirectory("rz-pace");
        String realHome = System.getProperty("user.home");
        BooleanSupplier realProbe = Video.providerProbe;
        long realPace = Video.paceMs;
        try {
            System.setProperty("user.home", home.toString());
            Path dir = home.resolve(".researchzosho").resolve("video");
            Files.createDirectories(dir.resolve("venv").resolve("bin"));
            Files.writeString(dir.resolve("wgcf-profile.conf"), "[Interface]\n");
            Files.writeString(dir.resolve("warp.env"), "VPN_TYPE=wireguard\n");
            Files.writeString(dir.resolve("venv").resolve("bin").resolve("yt-dlp"), "#!/bin/sh\n");
            Video.providerProbe = () -> true;
            Video.runner = (cmd, t) -> { ran.add(cmd); return new Video.Result(0, "{}"); };
            Video.paceMs = 400;
            // another process called YouTube just now and left its time in the file
            long other = System.currentTimeMillis();
            Files.writeString(dir.resolve("pace"), Long.toString(other));
            long t0 = System.currentTimeMillis();
            Video.ytdlp(List.of("ytsearch1:a"), Duration.ofSeconds(5));
            long afterFirst = System.currentTimeMillis();
            Video.ytdlp(List.of("ytsearch1:b"), Duration.ofSeconds(5));
            long afterSecond = System.currentTimeMillis();
            assertTrue(afterFirst - other >= 380, "the first call waited for the other process's call: " + (afterFirst - other) + " ms");
            assertTrue(afterSecond - afterFirst >= 380, "the second waited for the first: " + (afterSecond - afterFirst) + " ms");
            assertTrue(afterSecond - t0 < 5000, "and not much longer: " + (afterSecond - t0) + " ms");
            long stamped = Long.parseLong(Files.readString(dir.resolve("pace")).strip());
            assertTrue(stamped >= afterFirst - 50 && stamped <= afterSecond, "the file holds this process's last call for the next process to read: " + stamped);
            assertEquals(2, ran.size());
        } finally {
            System.setProperty("user.home", realHome);
            Video.providerProbe = realProbe;
            Video.paceMs = realPace;
        }
    }

    @Test
    void theDownloadsAreNamedForThisMachine() {
        String deno = Video.denoUrl();
        assertTrue(deno == null || deno.startsWith("https://github.com/denoland/deno/releases/latest/download/deno-") && deno.endsWith(".zip"), String.valueOf(deno));
    }
}
