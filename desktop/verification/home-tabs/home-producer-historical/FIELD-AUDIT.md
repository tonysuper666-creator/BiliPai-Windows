# Original Home card layout / actual Discovery consumer

Only four original persisted fields are prepared here. The full original `HomeSettings` mapper audit remains in the sibling `settings-home-dynamic-parity/FIELD-AUDIT.md`; its source/hash is pinned by this slice's `field-audit.json`. No constructor-only value is substituted for the actual persisted read expression.

| Field / real global `settings` key | Original constructor / persisted default | Prepared actual consumer |
|---|---|---|
| gridColumnCount / `grid_column_count` | 0 / 0 | Medium+ fixed columns; original positive fixed value wins before width preset. Original 0..6 settings menu; no new clamp of stored values. |
| gridColumnCountCompact / `grid_column_count_compact` | 0 / 0 | Compact independent memory; existing imported key and exact original setter apply to actual grid. Wide writes leave it intact. Original desktop pinch interaction is still missing; there is no invented compact-menu control. |
| homeFeedCardWidthPreset / `home_feed_card_width_preset` | AUTO0 / AUTO0 | AUTO180, COMPACT160, BALANCED200, WIDE260, ULTRA_WIDE320 minimum widths, original width-class bounds. Unknown value falls back AUTO without rewriting disk. |
| homeFeedCardStyle / `home_feed_card_style` | BILIPAI2 / BILIPAI2 | CURRENT16:9, OFFICIAL4:3, BILIPAI16:10; exact original single-column / Expanded+ exceptions. Unknown value falls back BILIPAI without rewriting disk. |

The original card layout supplies 6dp outer/item/vertical spacing, 1280dp content cap, and density title minimum/maximum lines. Actual desktop `LocalWindowInfo` / `LocalDensity` supplies the original Dp width-class resolver. The actual remaining feed viewport determines the available width because the desktop sidebar consumes part of the window. No synthetic Android fold posture or hinge sensor is introduced. Existing discovery page/filter/feedback/source/refresh keys remain unchanged; preference values never become pagination or browse-memory keys.

The three original Appearance controls retain their options, selection values and priority help. The Android-only bulleted-list drawable is bound to the already shared original semantic HOME_FEED icon renderer. Help strings are narrowed explicitly: unsupported pinch/fold behavior is not advertised, and original global cover-style support for search/related routes is not claimed. The original wide-only menu condition is retained for the current grid display mode.

The current Windows M3 Card/author/stat/actions remain in place. Only the real cover ratio and title min/max policy are added; `compactMetadata`, `compactStatsOnCover`, Story horizontal padding and the complete original ElegantVideoCard/StoryVideoCard composition remain gaps. Footer buttons use a desktop `FlowRow` with actual minimum48dp measured targets so the original denser columns cannot squeeze three actions into overlapping or narrow targets. Their enabled conditions and MID/BV/CID callbacks are preserved.

Other active original fields are not fake toggles here: display mode1, pinch, hero carousel/autoplay, duration legacy migration, full-title setting, publish-time visibility, UP avatars/badges, cover statistics, low-data cover requests, motion/glass, navigation and collapse controls. In particular hero constructor=true versus persisted=false, crash-consent constructor=true versus persisted=false, legacy duration fallback and frosted-glass migration are recorded rather than silently treated as this slice's defaults.

This is prepared production code with explicit consumer overlays. It is not yet a Root-integrated/native packaged acceptance result or full Home parity.
