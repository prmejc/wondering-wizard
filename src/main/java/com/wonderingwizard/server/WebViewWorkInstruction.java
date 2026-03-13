package com.wonderingwizard.server;

import com.wonderingwizard.events.WorkInstructionEvent;

/**
 * Wrapper around {@link WorkInstructionEvent} for webview serialization.
 * Ensures all work instruction fields are serialized to the frontend.
 *
 * @param workInstruction the wrapped work instruction event
 */
public record WebViewWorkInstruction(WorkInstructionEvent workInstruction) {
}
