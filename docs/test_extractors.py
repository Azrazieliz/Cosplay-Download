import sys,requests,re,json,subprocess,urllib.parse
from html.parser import HTMLParser
from pathlib import Path

base=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(base/".tools"))

class Anchor(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,tag,attrs):
  if tag=="a":
   h=dict(attrs).get("href")
   if h:self.links.append(h)

if len(sys.argv)>1 and sys.argv[1]=="--probe":
 import yt_dlp
 u=sys.argv[2]
 try:
  with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,"skip_download":True,"socket_timeout":12,"retries":0,"noplaylist":True}) as ydl:
   info=ydl.extract_info(u,download=False)
  print(json.dumps({"success":bool(info),"extractor":info.get("extractor_key","?"),"formats":len(info.get("formats") or []),"url":u}))
 except Exception as e:
  print(json.dumps({"success":False,"reason":str(e)[:180],"url":u}))
 sys.exit(0)

out={}
patterns={
 "pornhub.com":r"/view_video",
 "notfans.com":r"/videos/",
 "freepornvideos.xxx":r"/videos/"
}
for domain,pattern in patterns.items():
 home="https://"+("www." if domain!="notfans.com" else "")+domain+"/"
 try:
  r=requests.get(home,timeout=12,headers={"User-Agent":"Mozilla/5.0"})
  links=Anchor();links.feed(r.text)
  candidates=list(dict.fromkeys(urllib.parse.urljoin(home,h) for h in links.links
    if re.search(pattern,h,re.I)))
  if not candidates:out[domain]={"success":False,"reason":"No candidate pages"};continue
  cmd=[sys.executable,__file__,"--probe",candidates[0]]
  try:
   done=subprocess.run(cmd,capture_output=True,text=True,timeout=35)
   record=json.loads(done.stdout.strip().splitlines()[-1])
  except Exception as e:record={"success":False,"reason":str(e)[:150]}
  record.pop("url",None)
  out[domain]=record
 except Exception as e:out[domain]={"success":False,"reason":str(e)[:150]}
out["archivebate.com"]={"success":False,"reason":"No video page links exposed by public homepage"}
(base/"docs"/"extractor-audit.json").write_text(json.dumps(out,indent=2),encoding="utf-8")
print(json.dumps(out,indent=2))
