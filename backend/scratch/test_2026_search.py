import urllib.request
import urllib.parse
import json
import re

def test_live_search(query: str):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
    
    # 1. Live DuckDuckGo HTML Search for 2026 current info
    encoded = urllib.parse.quote(f"{query} 2026")
    url = f"https://html.duckduckgo.com/html/?q={encoded}"
    
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=6) as resp:
        html = resp.read().decode('utf-8', errors='ignore')
        snippets = re.findall(r'<a class="result__snippet[^>]*>(.*?)</a>', html, re.DOTALL)
        titles = re.findall(r'<a class="result__url"[^>]*>(.*?)</a>', html, re.DOTALL)
        
        results = []
        for i in range(min(5, len(snippets))):
            clean_snippet = re.sub(r'<[^>]+>', '', snippets[i]).strip()
            clean_title = re.sub(r'<[^>]+>', '', titles[i]).strip() if i < len(titles) else query
            results.append({"title": clean_title, "snippet": clean_snippet})
            
        print(f"RESULTS FOR '{query} 2026':")
        print(json.dumps(results, indent=2))

if __name__ == "__main__":
    test_live_search("Chief Minister of Tamil Nadu")
