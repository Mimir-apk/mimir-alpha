package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.MusicPlatformFolders
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier

/**
 * Which top-level folders of the Custom music folder are a platform's (`snes`, `ps2`, `pcgames`): the platform registry's own folder index, the one the game scanner
 * reads ROM folders by ([ExtensionPlatformClassifier.platformHintFromFolder]), so a music folder is a platform's under exactly the names a ROM folder is. The music scan
 * knows no platform (`:core-audio` has no `:core-model`), so the app hands it this answer when it starts ([com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary.load]).
 *
 * Only a name the registry knows counts. An artist's folder (`Queen`, `ABBA`, `U2`) is ordinary music, however short or plain its name: the review of the earlier update found that taking
 * every one-word folder for a platform's kept the artist's tracks out of general playback, and so out of Shuffle. A band whose name is also a platform's (`Genesis`) still
 * reads as that platform's folder.
 */
internal val AppMusicPlatformFolders = MusicPlatformFolders { folderName ->
    ExtensionPlatformClassifier.platformHintFromFolder(folderName)?.value
}
