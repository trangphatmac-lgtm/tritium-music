# Navigation modes

Choose **Settings → Appearance → Navigation mode**. The preference is saved and
applies to Explore, My playlists, Saved playlists, and playlist detail pages.
Global search results and cloud storage retain their existing interfaces.

| Mode | Presentation |
| --- | --- |
| List (default) | Existing recommendation cards, playlist libraries and track lists |
| Coverflow → OldSchool | Original `a3f70d2` camera, centre scaling and fading per-cover reflections |
| Coverflow → Mirror | Shared mirror plane and screen-space artwork reflections |
| Coverflow → Card | Single horizontal carousel for playlist libraries; hexagonal cards for songs |
| Wall | Infinite Bravais collage, repeating the source entries to fill the window |

Card and Wall draw across the whole window, behind the original top bar/sidebar
and transport. Blur and Liquid Glass capture this cover scene before drawing the
controls, so artwork remains visible through their materials. Controls take input
before the cover field. Card's central cover and caption respect the controls'
safe area; surrounding cards can continue underneath the navigation.

All views retain source order. Playlist details contain one entry per song,
including songs sharing an album and repeated occurrences of a song. Repeated
wall instances never add songs to the playback queue. A filtered song starts at
its original position in the full playlist. The old Coverflow button is removed.

## Browsing

- Click a side card to centre it; click the centred card or press Enter to open
  the playlist or play the song. Drag the library carousel horizontally; release
  to snap to the nearest card. Song cards can be panned in both directions.
- Wall playlist tiles open directly, flipping the wall into the song collection.
  The clicked slot becomes the first song, or the remembered song when enabled. Click a song tile to expand it to a
  6×6 cover while its neighbours rearrange; click again or press Enter to play.
  Escape collapses an expanded song before returning to the previous page.
- Arrows select the preceding/following entry; Home/End jump to the first/last;
  Page Up/Down move ten entries. Wheel advances Card or classic Coverflow.
- Drag Wall freely, scroll vertically, or use Ctrl + wheel to zoom. The wall
  repeats in every direction, including negative coordinates and sparse libraries.
- The **existing** top/side search field also filters the current cover view while
  typing (Ctrl/Cmd + F or G). Enter retains its original global online search.
  Escape clears the focused filter first. There is no additional search field.
- Mouse back/forward retains page history. Space continues to pause/resume.
- All Coverflow styles show a circular back button at the upper left of playlist
  details. Details fade in a dark background overlay; returning to a library
  removes it, including in OldSchool.

## Card options

Choose **Artwork only** or **Polaroid cards**. Library covers use Folia's single-row
centre enlargement, side fading and hover lighting. The focused library card is
only slightly wider than a song card, with room for two neighbours on each side
inside the navigation safe area. Song cards additionally use
full-bleed/square options and configurable size/opacity falloff (defaults 45%/40%).
Remember card position stores the focused item separately for up to 100 pages.
Opening a collection flips its sleeve horizontally while it flies into the song
grid and surrounding cards cascade in. The sleeve switches to the focused song
at the edge-on midpoint; the destination is drawn only once throughout the flip.

## Wall options

- **Solid:** artwork fills every non-seam position, even with just one playlist.
  The selected background still renders underneath and is revealed during transitions.
- **Partial:** 1–6 intentional transparent windows per twelve-slot block.
- **Clear:** transparent posters, revealing artwork on hover or keyboard focus.
- Collection stack edges, backdrop lyrics and backdrop blur remain configurable.
- The Bravais information strip is permanently hidden.

Posters use gradient scrims, large wrapped titles, creator/artist names and count
badges. Hover expands a poster by one seam width, retracts the paper edges and
fades the theme tint. Gutters align to physical pixels at every zoom and display
scale. Fine paper edges stay inside each tile's right/bottom gutter, leaving
intersections clear; narrow gutters reduce to one sheet instead of overlapping.
Missing artwork uses a coloured music-note poster. Visible
covers request a separate 1024px texture, so a cached list thumbnail cannot block
loading a sharp cover.

## Port and validation

Folia reference: `851af347`. Ports include Grid3DSlider's row transform,
hexViewport/falloff, the 12×8 block templates and their reflections, reserved
windows, wrap periods and nearest-copy lookup, all 48 exact 6×6 block reflows,
the stiffness-300/damping-34 reflow spring, 150/210ms tile flips, diagonal wall
waves and the 460ms sleeve flight, now combined with a 600ms horizontal flip. See
`src/main/resources/tritium/licenses/Folia-NOTICE.txt` for source attribution.

Unit coverage checks ordered and duplicate songs, original playback indices,
settings, complete viewport coverage at distant/negative coordinates, every
expanded block, nearest-copy lookup and seam ownership across zoom/DPI scales. OpenGL checks include both shells,
1/2/5-playlist walls, missing art, hover changes, flip/reflow/flight frames,
search and drag input, material captures beneath all shell regions, resizing and
classic reflections. Previews are in `target/ui-preview/navigation-*.png`.
