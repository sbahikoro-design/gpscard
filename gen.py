import asyncio, json, os, subprocess, urllib.request
L=json.load(open('lines.json'))
os.makedirs('voix',exist_ok=True)
async def edge():
    import edge_tts
    for row in L:
        id,txt=row[0],row[1]; app=len(row)>2
        for name,voice,rate,pitch in ([("","fr-FR-DeniseNeural","-4%","+0Hz")] if app else
              [("","fr-FR-HenriNeural","-6%","-2Hz"),("_remy","fr-FR-RemyMultilingualNeural","-4%","+0Hz")]):
            await edge_tts.Communicate(txt,voice,rate=rate,pitch=pitch).save(f"voix/{id}{name}.mp3")
try:
    asyncio.run(edge()); print("edge ok")
except Exception as e:
    print("edge échec:", e)
# Secours hors-ligne : Piper (voix neurales françaises)
base="https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/"
for v,path in [("tom","tom/medium/fr_FR-tom-medium"),("siwis","siwis/medium/fr_FR-siwis-medium")]:
    try:
        for ext in (".onnx",".onnx.json"): urllib.request.urlretrieve(base+path+ext, f"{v}{ext}")
        for row in L:
            id,txt=row[0],row[1]
            subprocess.run(["python","-m","piper","-m",f"{v}.onnx","--length-scale","1.08","-f",f"voix/{id}_piper_{v}.wav"],input=txt.encode(),check=True)
        print("piper", v, "ok")
    except Exception as e: print("piper", v, "échec:", e)
