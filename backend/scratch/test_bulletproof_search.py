import urllib.request
import urllib.parse
import json
import re
import time
from typing import Dict, Any, List

def execute_bulletproof_search(query: str) -> Dict[str, Any]:
    start_time = time.time()
    clean_query = query.strip()
    encoded = urllib.parse.quote(clean_query)
    raw_results = []
    
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }

    # Provider 1: DuckDuckGo HTML Search
    try:
        url = f"https://html.duckduckgo.com/html/?q={encoded}"
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=5) as resp:
            html = resp.read().decode('utf-8', errors='ignore')
            snippets = re.findall(r'<a class="result__snippet[^>]*>(.*?)</a>', html, re.DOTALL)
            titles = re.findall(r'<a class="result__url"[^>]*>(.*?)</a>', html, re.DOTALL)
            
            for i in range(min(5, len(snippets))):
                clean_snippet = re.sub(r'<[^>]+>', '', snippets[i]).strip()
                clean_title = re.sub(r'<[^>]+>', '', titles[i]).strip() if i < len(titles) else clean_query
                full_url = clean_title if clean_title.startswith("http") else f"https://{clean_title}"
                if clean_snippet:
                    raw_results.append({
                        "title": clean_title,
                        "snippet": clean_snippet,
                        "url": full_url
                    })
    except Exception as e:
        print(f"Provider 1 warning: {e}")

    # Provider 2: Wikipedia REST Search API
    if not raw_results:
        try:
            wiki_url = f"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={encoded}&format=json"
            wiki_req = urllib.request.Request(wiki_url, headers=headers)
            with urllib.request.urlopen(wiki_req, timeout=5) as wiki_resp:
                wiki_data = json.loads(wiki_resp.read().decode('utf-8'))
                search_hits = wiki_data.get("query", {}).get("search", [])
                for hit in search_hits[:4]:
                    clean_snippet = re.sub(r'<[^>]+>', '', hit.get("snippet", ""))
                    if clean_snippet:
                        raw_results.append({
                            "title": hit.get("title", clean_query),
                            "snippet": clean_snippet,
                            "url": f"https://en.wikipedia.org/wiki/{urllib.parse.quote(hit.get('title', ''))}"
                        })
        except Exception as e:
            print(f"Provider 2 warning: {e}")

    # Provider 3: DuckDuckGo Instant Answer API
    if not raw_results:
        try:
            ddg_api = f"https://api.duckduckgo.com/?q={encoded}&format=json&no_html=1"
            req = urllib.request.Request(ddg_api, headers=headers)
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                abstract = data.get("AbstractText")
                if abstract:
                    raw_results.append({
                        "title": data.get("Heading") or clean_query,
                        "snippet": abstract,
                        "url": data.get("AbstractURL") or f"https://en.wikipedia.org/wiki/{encoded}"
                    })
        except Exception as e:
            print(f"Provider 3 warning: {e}")

    evidence_chunks = []
    for r in raw_results[:5]:
        evidence_chunks.append({
            "title": r.get("title"),
            "url": r.get("url"),
            "chunk_text": r.get("snippet")
        })

    exec_ms = int((time.time() - start_time) * 1000)
    return {
        "success": len(evidence_chunks) > 0,
        "query": clean_query,
        "evidence_chunks": evidence_chunks,
        "count": len(evidence_chunks),
        "exec_ms": exec_ms
    }

if __name__ == "__main__":
    res = execute_bulletproof_search("who is the current Chief Minister of Tamilnadu")
    print(json.dumps(res, indent=2))
