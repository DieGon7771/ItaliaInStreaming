import json
from datetime import datetime

# Legge il file plugins.json
with open('plugins.json', 'r', encoding='utf-8') as f:
    plugins = json.load(f)

print(f"📊 Trovati {len(plugins)} plugin")

# Categorizza i plugin per status
status_groups = {
    1: [],  # 🟢 ATTIVI
    3: [],  # 🔵 BETA
    2: [],  # 🟡 LENTI
    0: []   # 🔴 DISATTIVATI
}

for plugin in plugins:
    status = plugin.get('status', 0)
    if status in status_groups:
        status_groups[status].append(plugin)

# Calcola statistiche
total = len(plugins)
attivi = len(status_groups[1])
beta = len(status_groups[3])
lenti = len(status_groups[2])
disattivati = len(status_groups[0])
funzionanti = attivi + beta + lenti  # MODIFICATO: aggiungi lenti

# LOG
print(f"🟢 Attivi: {attivi}")
print(f"🔵 Beta: {beta}")
print(f"🟡 Lenti: {lenti}")
print(f"🔴 Disattivati: {disattivati}")

# Salva dati per telegram_message.py
output = {
    'date': datetime.now().strftime('%d/%m/%Y'),
    'total': total,
    'attivi': attivi,
    'beta': beta,
    'lenti': lenti,
    'disattivati': disattivati,
    'funzionanti': funzionanti,
    'groups': {str(k): v for k, v in status_groups.items()}  # Converti chiavi in string
}

with open('plugin_data.json', 'w', encoding='utf-8') as f:
    json.dump(output, f, indent=2)

print(f"✅ Plugin analizzati: {total}")
