package com.zhulao.elder

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var rvContacts: RecyclerView
    private lateinit var adapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)
        
        adapter = ContactAdapter(
            ContactManager.getContacts(this),
            { contact -> confirmDelete(contact) },
            { contact -> moveContact(contact, -1) },
            { contact -> moveContact(contact, 1) }
        )
        rvContacts.adapter = adapter

        findViewById<Button>(R.id.btnAddContact).setOnClickListener {
            showAddContactDialog()
        }

        findViewById<Button>(R.id.btnRedownload).setOnClickListener {
            resetModel()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    private fun refreshList() {
        adapter.updateList(ContactManager.getContacts(this))
    }

    private fun showAddContactDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val etKeywords = view.findViewById<EditText>(R.id.etKeywords)
        
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val name = etName.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            val keywordsStr = etKeywords.text.toString().trim()
            
            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "姓名和电话不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val keywords = keywordsStr.split(Regex("[,，\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            
            // 默认添加名字作为关键词
            if (!keywords.contains(name)) {
                keywords.add(name)
            }
            
            val newContact = Contact(UUID.randomUUID().toString(), name, phone, keywords)
            ContactManager.addContact(this, newContact)
            refreshList()
            dialog.dismiss()
            Toast.makeText(this, "已添加: $name", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }

    private fun confirmDelete(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle("删除联系人")
            .setMessage("确定要删除 ${contact.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                ContactManager.deleteContact(this, contact.id)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveContact(contact: Contact, direction: Int) {
        val list = ContactManager.getContacts(this).toMutableList()
        val index = list.indexOfFirst { it.id == contact.id }
        if (index == -1) return
        
        val newIndex = index + direction
        if (newIndex in 0 until list.size) {
            java.util.Collections.swap(list, index, newIndex)
            ContactManager.saveContacts(this, list)
            refreshList()
        }
    }

    private fun resetModel() {
        try {
            val modelDir = File(filesDir, "model")
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            val zipFile = File(filesDir, "model.zip")
            if (zipFile.exists()) {
                zipFile.delete()
            }
            Toast.makeText(this, "模型已重置，请返回主页重新加载", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "重置失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
