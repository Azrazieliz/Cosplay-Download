import requests
from html.parser import HTMLParser
from urllib.parse import urljoin,urlparse
from collections import Counter
class Parser(HTMLParser):
    def __init__(self): super().__init__();self.urls=[]
    def handle_starttag(self,tag,attrs):
        if tag!="a":return
        d=dict(attrs)
        if "href" in d:self.urls.append(d["href"])
base="https://archivebate.com/"
r=requests.get(base,timeout=15,headers={"User-Agent":"Mozilla/5.0"})
p=Parser();p.feed(r.text)
folders=list(dict.fromkeys(urljoin(base,x) for x in p.urls if "/platform/" in x))[:3]
for url in folders:
    r=requests.get(url,timeout=15,headers={"User-Agent":"Mozilla/5.0"})
    parser=Parser();parser.feed(r.text)
    urls=[urlparse(urljoin(url,a)) for a in parser.urls]
    paths=[p.path for p in urls if p.hostname=="archivebate.com"]
    print(url,r.status_code,"internal",len(paths),"sections",Counter(x.strip("/").split("/")[0] for x in paths).most_common(10))
    print("candidate_paths", sorted(set("/".join(x.split("/")[:3]) for x in paths))[:25])
print("platform_examples",[x for x in p.urls if "platform" in x][:15])

