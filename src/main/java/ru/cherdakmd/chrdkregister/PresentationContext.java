package ru.cherdakmd.chrdkregister;

/** Personal plaintext labels for TAB, menu and auto messages. Never inject MiniMessage via placeholders. */
record PresentationContext(String passType, String authStatus, String authHint, String security, String navigation) {
    static PresentationContext of(RegistrationStore.PlayerAccount account, AccessManager.PlayerStage stage,
                                  long now, boolean trialEligible, int trialDays, Messages messages, String navigation) {
        boolean trial = account != null && account.hasActivePass(now) && "trial".equals(account.lastGrantSource)
                && account.passUntil == account.trialUntil;
        String passType = messages.plain(trial ? "presentation.trial" : account != null && account.hasActivePass(now)
                ? "presentation.pass" : "presentation.no-pass");
        String authHint = switch (stage) {
            case NEEDS_REGISTER -> trialEligible ? messages.plain("presentation.register-trial", "days", trialDays)
                    : messages.plain("presentation.register");
            case NEEDS_LOGIN -> messages.plain("presentation.login");
            case NEEDS_2FA -> messages.plain("presentation.two-factor");
            case NEEDS_2FA_SETUP -> messages.plain("presentation.two-factor-setup");
            case NO_PASS -> messages.plain("presentation.renew");
            case AUTHENTICATED -> messages.plain("presentation.play");
        };
        return new PresentationContext(passType, PlaceholderValues.describeStage(stage), authHint,
                messages.plain(account != null && account.isTotpEnabled() ? "presentation.secured" : "presentation.unsecured"),
                stage == AccessManager.PlayerStage.AUTHENTICATED ? navigation : "");
    }
    Object[] placeholders() {
        return new Object[]{"pass_type", passType, "auth_status", authStatus, "auth_hint", authHint,
                "security", security, "navigation", navigation};
    }
}
