package com.zhulao.elder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val keywords: List<String>
)

object ContactManager {
    private const val PREF_NAME = "DynamicContacts"
    private const val KEY_CONTACTS = "contacts_json"

    fun getAllContacts(context: Context): List<Contact> {
        // 1. 获取 App 内置的动态联系人
        val appContacts = getContacts(context).toMutableList()
        
        // 2. 获取系统通讯录联系人
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val systemContacts = getSystemContacts(context)
            // 合并时，如果电话号码重复，优先使用 App 内置的（因为可能设置了特殊关键词）
            for (sysC in systemContacts) {
                if (appContacts.none { it.phone == sysC.phone }) {
                    appContacts.add(sysC)
                }
            }
        }
        
        return appContacts
    }

    private fun getSystemContacts(context: Context): List<Contact> {
        val list = mutableListOf<Contact>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                ),
                null, null, null
            )
            
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: ""
                    var number = it.getString(numIdx) ?: ""
                    val id = it.getString(idIdx) ?: ""
                    
                    // 清理电话号码格式
                    number = number.replace(" ", "").replace("-", "")
                    
                    if (name.isNotEmpty() && number.isNotEmpty()) {
                        // 系统联系人默认没有特殊关键词，只有名字本身
                        val keywords = mutableListOf(name)
                        list.add(Contact("sys_$id", name, number, keywords))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getContacts(context: Context): List<Contact> {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_CONTACTS, "[]")
        val list = mutableListOf<Contact>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val name = obj.getString("name")
                val phone = obj.getString("phone")
                val keywordsArr = obj.getJSONArray("keywords")
                val keywords = mutableListOf<String>()
                for (j in 0 until keywordsArr.length()) {
                    keywords.add(keywordsArr.getString(j))
                }
                list.add(Contact(id, name, phone, keywords))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveContacts(context: Context, contacts: List<Contact>) {
        val arr = JSONArray()
        for (c in contacts) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            obj.put("phone", c.phone)
            val kArr = JSONArray()
            c.keywords.forEach { kArr.put(it) }
            obj.put("keywords", kArr)
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTACTS, arr.toString())
            .apply()
    }

    fun addContact(context: Context, contact: Contact) {
        val list = getContacts(context).toMutableList()
        list.add(contact)
        saveContacts(context, list)
    }

    fun deleteContact(context: Context, contactId: String) {
        val list = getContacts(context).toMutableList()
        list.removeAll { it.id == contactId }
        saveContacts(context, list)
    }
}
