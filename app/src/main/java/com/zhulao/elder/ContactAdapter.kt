package com.zhulao.elder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private var contacts: List<Contact>,
    private val onDelete: (Contact) -> Unit,
    private val onMoveUp: (Contact) -> Unit,
    private val onMoveDown: (Contact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvPhone: TextView = v.findViewById(R.id.tvPhone)
        val tvKeywords: TextView = v.findViewById(R.id.tvKeywords)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
        val btnUp: Button = v.findViewById(R.id.btnUp)
        val btnDown: Button = v.findViewById(R.id.btnDown)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = contacts[position]
        holder.tvName.text = c.name
        holder.tvPhone.text = c.phone
        holder.tvKeywords.text = "呼叫词: " + c.keywords.joinToString("，")
        holder.btnDelete.setOnClickListener { onDelete(c) }
        
        holder.btnUp.setOnClickListener { onMoveUp(c) }
        holder.btnDown.setOnClickListener { onMoveDown(c) }
        
        // Disable buttons at boundaries
        holder.btnUp.isEnabled = position > 0
        holder.btnUp.alpha = if (position > 0) 1.0f else 0.5f
        
        holder.btnDown.isEnabled = position < contacts.size - 1
        holder.btnDown.alpha = if (position < contacts.size - 1) 1.0f else 0.5f
    }

    override fun getItemCount() = contacts.size
    
    fun updateList(list: List<Contact>) {
        contacts = list
        notifyDataSetChanged()
    }
}
