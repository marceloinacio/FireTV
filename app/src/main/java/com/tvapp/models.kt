package com.tvapp

data class Category(
    val category_id: String,
    val category_name: String
)

data class Stream(
    val stream_id: Int,
    val name: String,
    val category_id: String,
    val stream_type: String?,
    val container_extension: String?
)

data class SeriesInfo(
    val series_id: Int,
    val name: String,
    val category_id: String,
    val seasons: Map<Int, List<Episode>> = emptyMap()
)

data class Episode(
    val episode_num: Int,
    val title: String,
    val season: Int
)

data class Season(
    val season_num: Int,
    val episodes: List<Episode>
)

data class Group(
    val name: String,
    val channels: List<Stream>
)

sealed class ListItem {
    data class GroupItem(val group: Group) : ListItem()
    data class ChannelItem(val stream: Stream) : ListItem()
    data class SeriesItem(val series: SeriesInfo) : ListItem()
    data class SeasonItem(val seriesId: Int, val seriesName: String, val season_num: Int, val episodeCount: Int) : ListItem()
    data class EpisodeItem(val seriesId: Int, val season: Int, val episode: Episode) : ListItem()

    companion object {
        fun from(groups: List<Group>): List<ListItem> {
            return groups.map { GroupItem(it) }
        }

        fun from(group: Group): List<ListItem> {
            return group.channels.map { ChannelItem(it) }
        }

        fun fromSeries(seriesList: List<SeriesInfo>): List<ListItem> {
            return seriesList.map { SeriesItem(it) }
        }

        fun fromSeriesSeasons(seriesId: Int, seriesName: String, seasons: Map<Int, List<Episode>>): List<ListItem> {
            return seasons.toSortedMap().map { (seasonNum, episodes) ->
                SeasonItem(seriesId, seriesName, seasonNum, episodes.size)
            }
        }

        fun fromSeasonEpisodes(seriesId: Int, season: Int, episodes: List<Episode>): List<ListItem> {
            return episodes.sortedBy { it.episode_num }.map { EpisodeItem(seriesId, season, it) }
        }
    }
}
