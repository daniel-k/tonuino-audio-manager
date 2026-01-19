# Bugs

- check text before/after (un)plugging usb drive
- shadow of `+` menu button
- close `+` menu when tapping somewhere else
- when item text of `+` menu overlaps with e.g. folder name it becomes unreadable
- connecting another USB drive doesn't work
- deprecation warning: `'static field INFO_OUTPUT_BUFFERS_CHANGED: Int' is deprecated.`
- when a folder gets updated, only invalidate this folder in the cache, not the whole cache
- track number constraint to max. 255 not taken into account in some regex, maybe centralize the definition
- caching is completely broken: removing folder and adding again show old folder
  metadata 

# Ideas

## Important

- rename the java/kotlin package from `com.example...` to something that makes sense
- write nfc tag from inside folder


## nice-to-have

- make mp3 quality configurable
- clean drive of unnecessary files
- transcoding progress in terms of total length vs encoded length
- send notification about copying progress
