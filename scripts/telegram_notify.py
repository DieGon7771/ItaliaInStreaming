#!/usr/bin/env python3
import os
import sys
import json
import requests

def send_telegram_message(bot_token, chat_id, message, parse_mode='HTML'):
    """Invia un messaggio a Telegram"""
    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    payload = {
        'chat_id': chat_id,
        'text': message,
        'parse_mode': parse_mode,
        'disable_web_page_preview': False
    }
    
    try:
        response = requests.post(url, json=payload, timeout=10)
        response.raise_for_status()
        return True
    except Exception as e:
        print(f"Errore invio messaggio: {e}", file=sys.stderr)
        return False

def format_commit_message(event_data):
    """Formatta il messaggio per i commit"""
    commits = event_data.get('commits', [])
    commits_count = len(commits)
    repo_name = event_data['repository']['full_name']
    branch = event_data['ref'].replace('refs/heads/', '')
    compare_url = event_data['compare']
    
    if commits_count == 1:
        commit = commits[0]
        commit_msg = commit['message'].split('\n')[0]
        author = commit['author']['name']
        message_text = f"{commit_msg} by <b>{author}</b>"
    else:
        message_text = f"<b>{commits_count} commits</b> by multiple authors"
    
    return f"""🔨 <b>{commits_count} new commit</b> to <b>{repo_name}</b> -> <b>{branch}</b>:
<a href="{compare_url}">{message_text}</a>"""

def format_issue_message(event_data):
    """Formatta il messaggio per le issue"""
    issue = event_data.get('issue', {})
    repo_name = event_data['repository']['full_name']
    issue_title = issue.get('title', 'Nessun titolo')
    issue_body = issue.get('body', '')
    issue_url = issue.get('html_url', '')
    author = issue.get('user', {}).get('login', 'Sconosciuto')
    
    # Tronca il corpo della issue a 500 caratteri
    if len(issue_body) > 500:
        issue_body = issue_body[:500] + '...'
    
    return f"""💢 <b>Issue opened on {repo_name}</b>

<b>{author}</b>

<b>{issue_title}</b>

{issue_body}

🔗 <a href="{issue_url}">Vedi su GitHub</a>"""

def main():
    # Legge il payload di GitHub dall'ambiente
    github_event_path = os.environ.get('GITHUB_EVENT_PATH')
    if not github_event_path:
        print("GITHUB_EVENT_PATH non trovato", file=sys.stderr)
        sys.exit(1)
    
    try:
        with open(github_event_path, 'r') as f:
            event_data = json.load(f)
    except Exception as e:
        print(f"Errore lettura evento: {e}", file=sys.stderr)
        sys.exit(1)
    
    # Determina il tipo di evento
    event_name = os.environ.get('GITHUB_EVENT_NAME')
    
    bot_token = os.environ.get('TELEGRAM_TOKEN')
    chat_id = os.environ.get('TELEGRAM_CHAT_ID')
    
    if not bot_token or not chat_id:
        print("TELEGRAM_TOKEN o TELEGRAM_CHAT_ID non impostati", file=sys.stderr)
        sys.exit(1)
    
    # Costruisce il messaggio in base al tipo di evento
    if event_name == 'push':
        message = format_commit_message(event_data)
    elif event_name == 'issues':
        # Invia solo quando l'issue viene aperta
        if event_data.get('action') == 'opened':
            message = format_issue_message(event_data)
        else:
            print("Evento issue non gestito", file=sys.stderr)
            sys.exit(0)
    else:
        print(f"Evento non supportato: {event_name}", file=sys.stderr)
        sys.exit(0)
    
    # Invia il messaggio
    success = send_telegram_message(bot_token, chat_id, message)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
