# 🔄 SyncStream – Guida Configurazione Cross Device Watch Sync

Questa guida spiega come configurare correttamente la sincronizzazione della visione tra più dispositivi in SyncStream.

## ⚠️ Importante
Prima di attivare la sincronizzazione è **obbligatorio** configurare le sezioni personalizzate, altrimenti la sync non funzionerà.

### Configurazione Sezioni Personalizzate
La Cross Device Watch Sync utilizza la pagina delle sezioni personalizzate per salvare e condividere i dati tra i dispositivi.

#### Creare le sezioni
1. Apri **SyncStream Settings**
2. Vai su **Configure Extension Sections**
3. Seleziona le sezioni che vuoi aggiungere alla home
4. Premi l'icona **💾 Salva** per confermare

#### Ordinare le sezioni
1. Vai su **Reorder Sections**
2. Puoi:
   - Spostare le sezioni usando le frecce
   - Oppure scambiare due sezioni cliccando prima una e poi l'altra
3. Premi di nuovo **💾 Salva**

✅ **Ora la pagina è pronta e puoi procedere con la sincronizzazione.**

## 🔐 Requisiti
⚠️ **Questa configurazione richiede:**
- Un progetto GitHub **privato**
- Un Personal Access Token (PAT)

## 🚀 Guida Configurazione Passo-Passo

### 1. Accedi a GitHub
Vai su [https://github.com](https://github.com) ed effettua l'accesso

### 2. Crea un nuovo progetto privato
Crea un nuovo progetto GitHub privato (qualsiasi template va bene)
**Prendi nota del numero del progetto** (es. #1, #2, ecc.)

### 3. Genera un Personal Access Token
Vai su: [https://github.com/settings/tokens/new](https://github.com/settings/tokens/new)
(Settings → Developer Settings → Personal Access Tokens → Tokens (Classic))

- Imposta **Expiration** su **No expiration**
- Seleziona gli scope:
  - **project** (acceso)
  - **read:project** (acceso)
- Clicca **Generate token**

🔐 **Copia e salva il token** (non verrà mostrato di nuovo)

## ⚙️ Configurazione in SyncStream 

### 4. Apri le impostazioni di Ultima
Vai su **SyncStream Settings → Cross Device Watch Sync → Login Data**

### 5. Inserisci i dati
- **Token** → incolla il PAT di GitHub
- **Project number** → inserisci il numero del progetto (es. 1)
- **Device name** → scegli un nome univoco per il dispositivo

### 6. Configura gli altri dispositivi
- Ripeti i passaggi 4–5 sugli altri dispositivi
- Usa un nome diverso per ogni dispositivo

## 🔁 Funzionamento della Sincronizzazione
- Attiva **Sync this device** sul dispositivo principale
- I contenuti sincronizzati appariranno sugli altri dispositivi che hanno:
  - SyncStream installato
  - Stesso Token e numero del project

## 📱 Gestione Dispositivi Collegati
- **Visualizzare i dispositivi collegati:**
  SyncStream Settings → Cross Device Watch Sync

- **Abilitare la cronologia di un dispositivo:**
  Attiva l'interruttore accanto al nome del dispositivo
  I contenuti appariranno nella home di SyncStream 

**Dopo ogni modifica ricordati di premere l'icona 💾 Salva in alto a destra**

## ⚠️ Note Importanti
Per far funzionare correttamente i contenuti sincronizzati:
- Tutti i dispositivi devono avere le stesse estensioni installate
- Le configurazioni delle estensioni devono essere identiche
- Il progetto GitHub deve rimanere privato

## 🎥 Video Tutorial
👉 [VideoTutorial](https://youtu.be/Uulp9KIqJ2c?feature=shared)
 
