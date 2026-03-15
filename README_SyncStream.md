# 🧩 SyncStream – Guida

Questa guida spiega come configurare **SyncStream** per sincronizzare automaticamente i dati tra più dispositivi utilizzando GitHub.

Con SyncStream puoi sincronizzare:

- Continua a guardare
- Repository installati (non plugin)
- Cronologia delle ricerche
- Film e serie TV segnati come preferiti, completati, ecc.

---

## ⚠️ Importante

Prima di collegare più dispositivi è **fondamentale registrare prima il dispositivo principale**.

Il dispositivo principale deve essere quello che **contiene già i dati che vuoi sincronizzare** (cronologia, preferiti, repository, ecc.).

Se registri prima un dispositivo secondario che non ha dati importanti, potresti **sovrascrivere i dati esistenti e perderli**.

👉 **Consiglio:**  
1. Configura prima **il dispositivo principale**  
2. Poi collega gli **altri dispositivi secondari**

---

## 🔐 Requisiti

Per utilizzare SyncStream è necessario:

- Un **progetto GitHub privato**
- Un **Personal Access Token (PAT)**

---

## 🚀 Guida Configurazione Passo-Passo

### 1. Accedi a GitHub

Vai su:

https://github.com

ed effettua l'accesso al tuo account.

---

### 2. Crea un nuovo progetto privato

Crea un nuovo **progetto GitHub privato**.

Qualsiasi template va bene.

👉 **Prendi nota del numero del progetto** (esempio: `#1`, `#2`, ecc.).

---

### 3. Genera un Personal Access Token

Vai su:

https://github.com/settings/tokens/new

Percorso completo:

Settings → Developer Settings → Personal Access Tokens → Tokens (Classic)

Configura il token così:

- Imposta **Expiration** su **No expiration**
- Attiva gli scope:
  - `project`
  - `read:project`

Poi clicca su:

**Generate token**

⚠️ **Copia e salva il token**, perché non verrà mostrato di nuovo.

---

## ⚙️ Configurazione in SyncStream

### 4. Apri le impostazioni del plugin

E vai su:

**Login**

---

### 5. Inserisci i dati

Compila i campi richiesti:

- **Token** → incolla il Personal Access Token di GitHub  
- **Project number** → inserisci il numero del progetto (esempio: `1`)

---

### 6. Attiva le funzioni di sincronizzazione

Attiva queste due opzioni:

- **Backup dei dati sul Cloud**
- **Recupera dati dal Cloud**

Poi premi:

💾 **Salva**

Se la configurazione è corretta, apparirà un messaggio che conferma che **il dispositivo è stato registrato correttamente**.

---

## 📱 Collegare altri dispositivi

Sugli altri dispositivi ripeti la stessa procedura:

1. Apri **SyncStream Settings**
2. Vai su **Login**
3. Inserisci:
   - lo stesso **Token**
   - lo stesso **Project number**
4. Attiva:
   - **Backup dei dati sul Cloud**
   - **Recupera dati dal Cloud**
5. Premi **💾 Salva**

Dopo la configurazione i dispositivi inizieranno a **sincronizzare automaticamente i dati**.

---

## ⏱️ Tempo di sincronizzazione

La sincronizzazione **non è istantanea**.

I dati vengono salvati e recuperati dal cloud, quindi potrebbe essere necessario **un po' di tempo prima che le modifiche compaiano sugli altri dispositivi**.

👉 Non aspettarti una sincronizzazione immediata: è normale che i contenuti sincronizzati appaiano **dopo qualche momento**.

---

## ⚠️ Note Importanti

Per far funzionare correttamente la sincronizzazione:

- Tutti i dispositivi devono avere **gli stessi plugin installati**
- Il progetto GitHub deve rimanere **privato**

---

## 🎥 Video Tutorial

Un video tutorial verrà aggiunto **in futuro...**
