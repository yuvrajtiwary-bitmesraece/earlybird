package io.github.yuvrajtiwary.earlybird.profiles;

import java.util.Arrays;
import java.util.Optional;

/**
 * The location options offered in the bot (spec section 3.11). Cities are stored by name in
 * {@code profiles.cities}; the two remote options are stored as flags. The short code goes in button data.
 */
public enum LocationChoice {
    BENGALURU("blr", "Bengaluru"),
    HYDERABAD("hyd", "Hyderabad"),
    PUNE("pune", "Pune"),
    CHENNAI("chn", "Chennai"),
    DELHI_NCR("ncr", "Delhi NCR"),
    MUMBAI("mum", "Mumbai"),
    KOLKATA("kol", "Kolkata"),
    AHMEDABAD("amd", "Ahmedabad"),
    REMOTE_INDIA("rin", "Remote (India)"),
    REMOTE_WORLDWIDE("rww", "Remote (worldwide)");

    private final String code;
    private final String label;

    LocationChoice(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean isRemote() {
        return this == REMOTE_INDIA || this == REMOTE_WORLDWIDE;
    }

    public static Optional<LocationChoice> byCode(String code) {
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
    }

    public static Optional<LocationChoice> byCity(String city) {
        return Arrays.stream(values()).filter(c -> !c.isRemote() && c.label.equals(city)).findFirst();
    }
}
