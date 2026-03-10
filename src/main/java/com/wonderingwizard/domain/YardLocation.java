package com.wonderingwizard.domain;

/**
 * Parses a yard position string (e.g. {@code Y-PTM-1L20E4}) into its
 * structural components: block, bay, row and height.
 * <p>
 * The prefix up to and including the last {@code -} is stripped, leaving
 * a 6-character location code parsed as:
 * <ul>
 *   <li>block  — characters 0-1 (e.g. {@code 1L})</li>
 *   <li>bay    — characters 2-3 (e.g. {@code 20})</li>
 *   <li>row    — character  4   (e.g. {@code E})</li>
 *   <li>height — character  5   (e.g. {@code 4})</li>
 * </ul>
 */
public record YardLocation(String block, String bay, String row, String height) {

    private static final int LOCATION_LENGTH = 6;

    /**
     * Parses a position string into a {@link YardLocation}.
     *
     * @param position the full position string (e.g. {@code Y-PTM-1L20E4})
     * @return the parsed layout, or {@code null} if the position is null, empty
     *         or the location part is not exactly 6 characters
     */
    public static YardLocation parse(String position) {
        if (position == null || position.isEmpty()) {
            return null;
        }

        int lastDash = position.lastIndexOf('-');
        String location = lastDash >= 0 ? position.substring(lastDash + 1) : position;

        if (location.length() != LOCATION_LENGTH) {
            return null;
        }

        return new YardLocation(
                location.substring(0, 2),
                location.substring(2, 4),
                location.substring(4, 5),
                location.substring(5, 6)
        );
    }
}
