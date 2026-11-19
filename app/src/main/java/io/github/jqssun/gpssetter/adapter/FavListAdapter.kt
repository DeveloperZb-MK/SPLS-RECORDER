package io.github.jqssun.gpssetter.adapter

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.splsrecorder.zb.R
import io.github.jqssun.gpssetter.room.Favorite

class FavListAdapter : ListAdapter<Favorite, FavListAdapter.ViewHolder>(FavListComparetor()) {

    var onItemClick: ((Favorite) -> Unit)? = null
    var onItemDelete: ((Favorite) -> Unit)? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val row = view as LinearLayout
        private val address: TextView = row.getChildAt(0) as TextView
        private val delete: ImageView = row.getChildAt(1) as ImageView

        fun bind(favorite: Favorite) {
            address.text = favorite.address
            delete.setOnClickListener { onItemDelete?.invoke(favorite) }
            address.setOnClickListener { onItemClick?.invoke(favorite) }
        }
    }

    class FavListComparetor : DiffUtil.ItemCallback<Favorite>() {
        override fun areItemsTheSame(oldItem: Favorite, newItem: Favorite) = oldItem.address == newItem.address
        override fun areContentsTheSame(oldItem: Favorite, newItem: Favorite) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20), dp(5), 0, dp(5))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val address = TextView(context).apply {
            text = context.getString(R.string.address)
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Widget_TextView_SpinnerItem)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        val delete = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = context.getString(R.string.delete_favorite_item)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) }
        }
        row.addView(address)
        row.addView(delete)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
