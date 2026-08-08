package com.dialerapp

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private var contacts: List<Contact>,
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onItemLongClick: (Int) -> Unit = {},
    private val onHistoryClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>()
{

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    {
        val tvId: TextView = view.findViewById(R.id.tvId)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvPhone: TextView = view.findViewById(R.id.tvPhone)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val btnHistory: ImageButton = view.findViewById(R.id.btnHistory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
    {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int)
    {
        val contact = contacts[position]
        holder.tvId.text = contact.id.toString()
        holder.tvName.text = contact.name
        holder.tvPhone.text = contact.phone
        if (contact.callDuration.isNotEmpty()) {
            holder.tvDuration.text = "⏱ ${contact.callDuration}"
            holder.tvDuration.visibility = View.VISIBLE
        } else {
            holder.tvDuration.visibility = View.GONE
        }
        holder.tvStatus.text = when (contact.status)
        {
            "pending"        -> holder.itemView.context.getString(R.string.status_pending)
            "dialing"        -> holder.itemView.context.getString(R.string.status_dialing)
            "dialed"         -> holder.itemView.context.getString(R.string.status_dialed)
            "answered"       -> holder.itemView.context.getString(R.string.status_answered)
            "no_answer"      -> holder.itemView.context.getString(R.string.status_no_answer)
            "busy"           -> holder.itemView.context.getString(R.string.status_busy)
            "callback_later" -> holder.itemView.context.getString(R.string.status_callback_later)
            "not_interested" -> holder.itemView.context.getString(R.string.status_not_interested)
            "wrong_number"   -> holder.itemView.context.getString(R.string.status_wrong_number)
            else             -> contact.status.replace("_", " ")
        }

        // Highlight current row
        if (position == currentIndex)
        {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.color_highlight_row))
            holder.tvName.setTypeface(null, Typeface.BOLD)
        }
        else
        {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.tvName.setTypeface(null, Typeface.NORMAL)
        }

        // Color-code status
        holder.tvStatus.setTextColor(when (contact.status)
        {
            "pending" -> Color.GRAY
            "dialing", "dialed" -> Color.parseColor("#1565C0")
            "answered" -> Color.parseColor("#2E7D32")
            "no_answer", "busy" -> Color.parseColor("#E65100")
            "not_interested", "wrong_number" -> Color.RED
            "callback_later" -> Color.parseColor("#6A1B9A")
            else -> Color.DKGRAY
        })

        if (contact.callHistory.size > 1) {
            holder.btnHistory.visibility = View.VISIBLE
            holder.btnHistory.setOnClickListener { onHistoryClick(position) }
        } else {
            holder.btnHistory.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(position) }
        holder.itemView.setOnLongClickListener { onItemLongClick(position); true }
    }

    override fun getItemCount() = contacts.size

    fun updateData(newContacts: List<Contact>, newIndex: Int)
    {
        contacts = newContacts
        currentIndex = newIndex
        notifyDataSetChanged()
    }
}
