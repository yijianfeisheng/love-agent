<template>
  <div class="home">
    <div class="status-bar" role="status">
      <span class="status-text">
        {{ listening ? '正在聆听…' : '语音未开启' }}
      </span>
      <span v-if="transcript" class="transcript">“{{ transcript }}”</span>
    </div>

    <div class="grid">
      <BigTile
        label="语音呼叫"
        icon="🎤"
        bgColor="#1f6feb"
        @click="toggleVoice"
      />
      <BigTile
        label="拨打110"
        icon="🛡️"
        bgColor="#e03131"
        @click="callNumber('110')"
      />
      <BigTile
        label="拨打120"
        icon="🚑"
        bgColor="#0ca678"
        @click="callNumber('120')"
      />
      <BigTile
        label="拨打119"
        icon="🚒"
        bgColor="#d9480f"
        @click="callNumber('119')"
      />
      <BigTile
        label="联系人"
        icon="👨‍👩‍👧"
        bgColor="#6741d9"
        @click="showContacts = true"
      />
      <BigTile
        label="设置"
        icon="⚙️"
        bgColor="#495057"
        @click="showSettings = true"
      />
    </div>

    <div v-if="showContacts" class="sheet">
      <div class="sheet-header">
        <div class="sheet-title">联系人</div>
        <button class="sheet-close" @click="showContacts = false">关闭</button>
      </div>
      <div class="contacts">
        <div
          v-for="(c, idx) in contacts"
          :key="c.name + idx"
          class="contact-item"
        >
          <div class="contact-name">{{ c.name }}</div>
          <div class="contact-number">{{ c.number }}</div>
          <div class="contact-actions">
            <button class="action call" @click="callNumber(c.number)">拨打</button>
            <button class="action del" @click="removeContact(idx)">删除</button>
          </div>
        </div>
        <div class="contact-form">
          <input
            class="input"
            v-model.trim="newContact.name"
            placeholder="姓名（如：儿子）"
          />
          <input
            class="input"
            v-model.trim="newContact.number"
            placeholder="电话号码"
            inputmode="tel"
          />
          <button class="action add" @click="addContact">添加联系人</button>
        </div>
        <div class="hint">
          语音识别支持直接说“110”、“120”、“119”或联系人姓名。
        </div>
      </div>
    </div>

    <div v-if="showSettings" class="sheet">
      <div class="sheet-header">
        <div class="sheet-title">设置</div>
        <button class="sheet-close" @click="showSettings = false">关闭</button>
      </div>
      <div class="settings">
        <div class="setting-item">
          <div class="setting-label">语音语言</div>
          <select v-model="speechLang" class="select">
            <option value="zh-CN">中文（简体）</option>
            <option value="zh-TW">中文（繁体）</option>
            <option value="en-US">English</option>
          </select>
        </div>
        <div class="setting-item">
          <div class="setting-label">语音识别</div>
          <button class="action" @click="requestMic">授予麦克风权限</button>
        </div>
        <div class="setting-item">
          <div class="setting-label">离线语音说明</div>
          <div class="setting-desc">
            本应用不依赖服务器；是否离线识别取决于设备和浏览器对离线语音的支持。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import BigTile from './BigTile'

export default {
  name: 'Home',
  components: { BigTile },
  data () {
    return {
      listening: false,
      transcript: '',
      speech: null,
      speechLang: 'zh-CN',
      showContacts: false,
      showSettings: false,
      contacts: [],
      newContact: { name: '', number: '' }
    }
  },
  created () {
    this.contacts = this.loadContacts()
    this.initSpeech()
  },
  methods: {
    initSpeech () {
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition
      if (!SR) {
        this.speech = null
        return
      }
      const r = new SR()
      r.lang = this.speechLang
      r.continuous = true
      r.interimResults = false
      r.onresult = (e) => {
        const i = e.resultIndex
        const text = e.results[i] && e.results[i][0] ? e.results[i][0].transcript.trim() : ''
        if (text) {
          this.transcript = text
          this.processTranscript(text)
        }
      }
      r.onerror = () => {}
      r.onend = () => {
        if (this.listening) {
          try { r.start() } catch (e) {}
        }
      }
      this.speech = r
    },
    toggleVoice () {
      if (!this.speech) {
        alert('当前设备不支持语音识别，请使用拨号按钮')
        return
      }
      if (this.listening) {
        this.stopVoice()
      } else {
        this.startVoice()
      }
    },
    startVoice () {
      try {
        this.transcript = ''
        this.listening = true
        this.speech.lang = this.speechLang
        this.speech.start()
      } catch (e) {
        this.listening = false
      }
    },
    stopVoice () {
      try { this.speech.stop() } catch (e) {}
      this.listening = false
    },
    requestMic () {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        alert('无法请求麦克风权限')
        return
      }
      navigator.mediaDevices.getUserMedia({ audio: true })
        .then((stream) => {
          stream.getTracks().forEach(t => t.stop())
          alert('已授予麦克风权限')
        })
        .catch(() => {
          alert('麦克风权限未授予')
        })
    },
    processTranscript (text) {
      const normalized = text.replace(/\s+/g, '').toLowerCase()
      if (normalized.includes('110') || normalized.includes('一一零') || normalized.includes('报警')) {
        this.callNumber('110')
        return
      }
      if (normalized.includes('120') || normalized.includes('一二零') || normalized.includes('救护')) {
        this.callNumber('120')
        return
      }
      if (normalized.includes('119') || normalized.includes('一一九') || normalized.includes('火警')) {
        this.callNumber('119')
        return
      }
      const match = this.contacts.find(c => normalized.includes(c.name.toLowerCase()))
      if (match) {
        this.callNumber(match.number)
      }
    },
    callNumber (num) {
      this.stopVoice()
      window.location.href = `tel:${num}`
    },
    loadContacts () {
      try {
        const raw = localStorage.getItem('zl.contacts')
        if (raw) return JSON.parse(raw)
      } catch (e) {}
      return [
        { name: '儿子', number: '13800000000' },
        { name: '女儿', number: '13900000000' }
      ]
    },
    saveContacts () {
      try {
        localStorage.setItem('zl.contacts', JSON.stringify(this.contacts))
      } catch (e) {}
    },
    addContact () {
      const { name, number } = this.newContact
      if (!name || !number) return
      this.contacts.push({ name, number })
      this.newContact = { name: '', number: '' }
      this.saveContacts()
    },
    removeContact (idx) {
      this.contacts.splice(idx, 1)
      this.saveContacts()
    }
  },
  watch: {
    speechLang () {
      if (this.speech) this.speech.lang = this.speechLang
    }
  }
}
</script>

<style scoped>
.home {
  padding: 12px;
}
.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 24px;
  padding: 12px 16px;
  border-radius: 16px;
  background: #f1f3f5;
  color: #333;
  margin-bottom: 12px;
}
.status-text {
  font-weight: 700;
}
.transcript {
  font-size: 20px;
  color: #555;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 70vh;
  background: #fff;
  border-top-left-radius: 24px;
  border-top-right-radius: 24px;
  box-shadow: 0 -8px 24px rgba(0,0,0,0.2);
  padding: 16px;
  overflow-y: auto;
}
.sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sheet-title {
  font-size: 28px;
  font-weight: 800;
}
.sheet-close {
  font-size: 22px;
  border: none;
  background: #eee;
  border-radius: 10px;
  padding: 8px 16px;
}
.contacts {
  font-size: 24px;
}
.contact-item {
  display: grid;
  grid-template-columns: 2fr 2fr auto;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px solid #f1f3f5;
}
.contact-name {
  font-weight: 700;
}
.contact-number {
  color: #495057;
}
.contact-actions {
  display: flex;
  gap: 8px;
}
.action {
  font-size: 22px;
  padding: 8px 14px;
  border-radius: 10px;
  border: none;
  background: #1f6feb;
  color: #fff;
}
.action.call { background: #0ca678; }
.action.del { background: #e03131; }
.action.add { background: #2f9e44; }
.contact-form {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  padding: 12px 0;
}
.input, .select {
  font-size: 22px;
  padding: 10px 12px;
  border: 2px solid #ced4da;
  border-radius: 12px;
}
.hint {
  margin-top: 10px;
  font-size: 20px;
  color: #666;
}
.settings {
  font-size: 24px;
}
.setting-item {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  border-bottom: 1px solid #f1f3f5;
}
.setting-label {
  font-weight: 700;
}
.setting-desc {
  grid-column: 1 / -1;
  font-size: 20px;
  color: #555;
}
</style>
