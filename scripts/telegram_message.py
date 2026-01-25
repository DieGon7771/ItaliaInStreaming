import json

# Carica i dati
with open('plugin_data.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Emoji e nomi
status_names = {
    1: ("🟢", "ATTIVI"),
    3: ("🔵", "BETA"), 
    2: ("🟡", "LENTI"),
    0: ("🔴", "DISATTIVATI")
}

lines = []

# Header
lines.append("🏆 REPORT STATO REPOSITORY")
lines.append(f"📅 Generato il: {data['date']}")
lines.append("")

# Per ogni status
for status_code in [1, 3, 2, 0]:
    emoji, name = status_names[status_code]
    plugins = data['groups'].get(str(status_code), [])
    
    if plugins:
        lines.append(f"{emoji} {name}: {len(plugins)}")
        for plugin in plugins:
            lines.append(f"   • {plugin.get('name', 'Sconosciuto')}")
        lines.append("")

# Statistiche
attivi = len(data['groups'].get('1', []))
beta = len(data['groups'].get('3', []))
lenti = len(data['groups'].get('2', []))  # Include i lenti
total = data['total']
funzionanti = attivi + beta + lenti  # Attivi + Beta + Lenti
salute_percent = int((funzionanti / total) * 100) if total > 0 else 0

salute_emoji = "🟢" if salute_percent >= 70 else "🟡" if salute_percent >= 40 else "🔴"
lines.append(f"Salute repository: {salute_emoji}{salute_percent}%")
lines.append(f"Plugin funzionanti: {funzionanti}/{total}")
lines.append("")

# Link installazione
install_url = f"https://t.me/c/1978830401/1000"
lines.append(f"📦 INSTALLA: [CLICCA QUI]({install_url})")

message = "\n".join(lines)

# Salva per telegram
with open('telegram_msg.txt', 'w', encoding='utf-8') as f:
    f.write(message)

print("✅ Messaggio generato!")
print("\n" + "="*50)
print(message)
print("="*50)
