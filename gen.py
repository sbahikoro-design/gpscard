import asyncio, json, os, edge_tts
L=json.load(open('lines.json'))
VOIX={"bs":"fr-FR-HenriNeural","app":"fr-FR-DeniseNeural"}
async def main():
    os.makedirs('voix',exist_ok=True)
    for row in L:
        id,txt=row[0],row[1]
        who="app" if len(row)>2 else "bs"
        for name,voice,rate,pitch in [("",VOIX[who],"-6%","-2Hz"),("_remy","fr-FR-RemyMultilingualNeural","-4%","+0Hz")]:
            if who=="app" and name: continue
            c=edge_tts.Communicate(txt,voice,rate=rate,pitch=pitch)
            await c.save(f"voix/{id}{name}.mp3")
asyncio.run(main())
