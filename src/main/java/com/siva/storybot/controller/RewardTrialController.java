package com.siva.storybot.controller;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.service.RewardTrialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reward")
@RequiredArgsConstructor
public class RewardTrialController {

    private final RewardTrialService rewardTrialService;
    private final TelegramConfig telegramConfig;

    /**
     * Simple health/status endpoint.
     *
     * This endpoint does NOT activate any reward.
     *
     * Example:
     * GET /reward/status
     */
    @GetMapping(
            value = "/status",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RewardStatusResponse> status() {

        String botUsername = sanitizeBotUsername(
                telegramConfig.getBotUsername()
        );

        return ResponseEntity.ok(
                new RewardStatusResponse(
                        rewardTrialService.isEnabled(),
                        botUsername,
                        RewardTrialService.REWARD_ACCESS_MINUTES,
                        RewardTrialService.REWARD_LINK_MINUTES,
                        "DIRECT_TELEGRAM"
                )
        );
    }

    /**
     * Legacy endpoint.
     *
     * Old ShrtFly links may still point here.
     * They must NEVER activate a reward anymore.
     *
     * New flow:
     *
     * ShrtFly
     *      ->
     * https://t.me/<BOT>?start=rw_<TOKEN>
     */
    @GetMapping(
            value = "/landing",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> oldLanding() {

        return legacyResponse();
    }

    /**
     * Legacy POST endpoint.
     *
     * Kept only so old browser pages do not produce an ugly
     * 404/405 response.
     *
     * This endpoint NEVER activates a reward.
     */
    @PostMapping(
            value = "/complete",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> oldComplete() {

        return legacyResponse();
    }

    private ResponseEntity<String> legacyResponse() {

        String botUsername = sanitizeBotUsername(
                telegramConfig.getBotUsername()
        );

        String telegramUrl = botUsername.isBlank()
                ? "https://t.me/"
                : "https://t.me/" + botUsername + "?start=reward_retry";

        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport"
                          content="width=device-width, initial-scale=1">

                    <meta name="robots"
                          content="noindex,nofollow,noarchive">

                    <title>Reward Link Updated</title>

                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            background: #f5f7fb;
                            margin: 0;
                            padding: 24px;
                            color: #1f2937;
                        }

                        .card {
                            max-width: 520px;
                            margin: 10vh auto;
                            background: white;
                            border-radius: 18px;
                            padding: 28px;
                            text-align: center;
                            box-shadow: 0 12px 35px rgba(0,0,0,.08);
                        }

                        h2 {
                            color: #b45309;
                        }

                        p {
                            line-height: 1.6;
                        }

                        a {
                            display: inline-block;
                            margin-top: 18px;
                            padding: 12px 18px;
                            border-radius: 10px;
                            background: #229ED9;
                            color: white;
                            text-decoration: none;
                            font-weight: 700;
                        }
                    </style>
                </head>

                <body>

                    <div class="card">

                        <h2>Old Reward Link</h2>

                        <p>
                            This reward link belongs to the previous
                            reward system.
                        </p>

                        <p>
                            Please return to Telegram and request a
                            new 1-hour reward link.
                        </p>

                        <a href="%s">
                            Return to Telegram
                        </a>

                    </div>

                </body>
                </html>
                """.formatted(
                escapeHtmlAttribute(telegramUrl)
        );

        return ResponseEntity
                .status(HttpStatus.GONE)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header(
                        "X-Robots-Tag",
                        "noindex, nofollow, noarchive"
                )
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private String sanitizeBotUsername(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("@", "")
                .trim();
    }

    private String escapeHtmlAttribute(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public record RewardStatusResponse(
            boolean enabled,
            String botUsername,
            int rewardMinutes,
            int linkExpiryMinutes,
            String mode
    ) {
    }
}