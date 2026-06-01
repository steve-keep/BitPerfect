package com.bitperfect.core.services

import com.bitperfect.core.models.DiscMetadata

interface ArtworkResolver {
    suspend fun resolveArtwork(metadata: DiscMetadata): ResolvedArtwork?
}
