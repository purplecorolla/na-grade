package app.nagrade.api.setlist

import org.springframework.data.jpa.repository.JpaRepository

interface ArtistMappingRepository : JpaRepository<ArtistMapping, String>
