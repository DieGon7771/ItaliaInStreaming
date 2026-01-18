import json
import os

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

# Costruisci il messaggio
lines = []

# Header
lines.append("🏆 REPORT STATO REPOSITORY")
lines.append(f"📅 Generato il: {data['date']}")
lines.append("━" * 40)

# Per ogni status
for status_code in [1, 3, 2, 0]:
    emoji, name = status_names[status_code]
    plugins = data['groups'][str(status_code)]
    
    if plugins:
        lines.append(f"\n{emoji} {name}: {len(plugins)}")
        for plugin in plugins:
            lines.append(f"   • {plugin.get('name', 'Sconosciuto')}")
    else:
        lines.append(f"\n{emoji} {name}: 0")

# Footer
lines.append("\n━" * 40)

# Salute repository
salute_emoji = "🟢" if data['salute'] >= 70 else "🟡" if data['salute'] >= 40 else "🔴"
lines.append(f"• Salute repository: {salute_emoji}{data['salute']}%")
lines.append(f"• Plugin funzionanti: {data['funzionanti']}/{data['total']}")

# LINK INSTALLAZIONE
#username = "TUO_USERNAME_GITHUB"  
#repo = "TUO_REPOSITORY"          

install_url = f"https://t.me/c/1978830401/1000"
lines.append(f"\n📦 INSTALLA: [CLICCA QUI]({install_url})")

# Unisci tutto
message = "\n".join(lines)

# Salva per telegram
with open('telegram_msg.txt', 'w', encoding='utf-8') as f:
    f.write(message)

print("✅ Messaggio generato!")
print("\n" + "="*50)
print(message)
print("="*50)
