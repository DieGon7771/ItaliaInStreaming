import json
from datetime import datetime

# Legge il file plugins.json
with open('plugins.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

plugins = data.get('plugins', [])

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
funzionanti = attivi + len(status_groups[3])  # Attivi + Beta
salute = int((funzionanti / total) * 100) if total > 0 else 0

# Salva dati per telegram_message.py
output = {
    'date': datetime.now().strftime('%d/%m/%Y'),
    'total': total,
    'attivi': attivi,
    'funzionanti': funzionanti,
    'salute': salute,
    'groups': status_groups
}

with open('plugin_data.json', 'w', encoding='utf-8') as f:
    json.dump(output, f, indent=2)

print(f"✅ Plugin analizzati: {total}")
