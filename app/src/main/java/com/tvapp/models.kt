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

data class Group(
    val name: String,
    val channels: List<Stream>
)

sealed class ListItem {
    data class GroupItem(val group: Group) : ListItem()
    data class ChannelItem(val stream: Stream) : ListItem()

    companion object {
        fun from(groups: List<Group>): List<ListItem> {
            return groups.map { GroupItem(it) }
        }

        fun from(group: Group): List<ListItem> {
            return group.channels.map { ChannelItem(it) }
        }
    }
}
