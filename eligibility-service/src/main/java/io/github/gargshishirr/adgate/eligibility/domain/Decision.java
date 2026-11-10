package io.github.gargshishirr.adgate.eligibility.domain;

/** The eligibility verdict for one candidate ad, with a structured reason and a human-readable detail. */
public record Decision(String adId, String campaignId, boolean eligible, ReasonCode reason, String detail) {

    public static Decision eligible(String adId, String campaignId, String detail) {
        return new Decision(adId, campaignId, true, ReasonCode.ELIGIBLE, detail);
    }

    public static Decision declined(String adId, String campaignId, ReasonCode reason, String detail) {
        if (reason == ReasonCode.ELIGIBLE) {
            throw new IllegalArgumentException("A declined decision needs a decline reason");
        }
        return new Decision(adId, campaignId, false, reason, detail);
    }
}
