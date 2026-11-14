# Polarka Wallet: krátký návod

Polarka Wallet je zkušební verze. Prošla trojí kontrolou, zatím ne kontrolou nezávislého lidského odborníka.
Mějte v ní jen malé částky. Větší úspory patří na papír (studená adresa).

## Před instalací

- Telefon s Androidem 12 nebo novějším a se zámkem obrazovky. S otiskem prstu je to pohodlné.
- Balíček (`.apk`) ze stránky projektu a dva otisky zveřejněné vedle něj.
- Balíček před instalací ověřte. Na počítači:

      sha256sum polarka-wallet-X.Y.Z.apk
      apksigner verify --print-certs polarka-wallet-X.Y.Z.apk

  První číslo se musí rovnat zveřejněnému SHA-256 daného vydání. SHA-256 certifikátu musí být u každého vydání
  `f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b`. Pokud se cokoli liší, soubor neinstalujte.
- Android se zeptá, zda smí aplikace, kterou jste soubor otevřeli, instalovat. Povolte to pro tento jeden případ.

## První spuštění: uzel

Peněženka nemá vlastní server. Na stav vašich adres se ptá uzlu sítě TrueNorth.

- **Veřejný uzel** je rychlá cesta: potvrďte nabídnutou adresu, nebo napište jinou. Veřejný uzel vidí vaše adresy
  a vaši internetovou adresu a mohl by vám ukázat nepravdivý zůstatek nebo platbu zadržet. Mince vám vzít nemůže.
- **Vlastní uzel** nechá vaše adresy jen vám. Viz poslední kapitolu.

Jednou spusťte vlastní test (první obrazovka nebo Více). Ukáže, že kód pro klíče na vašem telefonu funguje.

## Založení peněženky

1. Přehled, "Vytvořit novou peněženku". Telefon ukáže 24 slov.
2. Opište je rukou na papír, v daném pořadí. Nefoťte je, nikam je nepište, neukládejte je do žádné aplikace.
   Kdo má slova, má mince. Bez nich znamená ztracený nebo rozbitý telefon ztracené mince.
3. Aplikace se zeptá na tři ze slov a potom slova zapečetí vaším otiskem nebo zámkem obrazovky.
4. Papír uložte tam, kam se nedostane oheň, voda ani zvědaví lidé.

Pokud v telefonu zrušíte zámek obrazovky, Android zničí klíč, který slova chrání. Aplikace vám to oznámí;
peněženku pak vrátí jen papír.

## Příjem

Záložka Přijmout ukáže adresu a její QR kód. Dejte ji plátci. Platba se objeví po svém prvním bloku, obvykle
během několika minut; dřív ji peněženka nevidí. Po použití adresy nabídne peněženka novou; staré adresy fungují dál.

## Odeslání

1. Odeslat: vložte adresu nebo načtěte její QR kód, napište částku (nebo zaškrtněte "Odeslat vše"), zvolte poplatek.
2. "Zkontrolovat platbu" ukáže adresu, částku, poplatek a to, co se vám vrátí. Adresu porovnejte s jejím zdrojem
   znak po znaku.
3. "Podepsat otiskem a odeslat". Odeslanou platbu nelze vzít zpět.

Dokud nepřijde blok, je platba v Přehledu uvedena jako čekající a její mince se znovu nenabízejí. Pokud uzel
nepotvrdí, že platbu převzal, aplikace řekne, že výsledek není znám. Neplaťte pak znovu: za minutu obnovte
přehled a aplikace ukáže, zda platba čeká na blok, nebo neproběhla.

Platba, která neproběhla, zůstane v Přehledu jako "Neproběhla, ještě může dorazit": uzel, kterého se aplikace
ptá, ji nedrží, ale jiný uzel ji držet může. Zaplatit znovu můžete hned. Aplikace postaví novou platbu na
minci té staré, takže dorazit může vždy jen jedna z nich, a záznam odstraní, jakmile o věci rozhodne blok.
"Zapomenout tuto platbu" je pro vzácný případ, kdy záznam zůstává a vy máte jistotu, že stará platba už
dorazit nemůže.

## Obnova peněženky

Více, "Obnova peněženky": napište 12 nebo 24 slov oddělených mezerami. Mince se najdou v řetězci, ať byla
peněženka používaná jakkoli dlouho. Vyzkoušejte to jednou s malou částkou, než se na to spolehnete: je to
jediná zkouška, že máte papír správně.

Sama od sebe aplikace prohlíží prvních 200 adres peněženky, u veřejného uzlu 40. Pokud uzel ukazuje, že jich
je použito víc, zeptá se Přehled, zda má aplikace hledat dál. Řekněte ano, pokud jste peněženku opravdu tolik
používali. Pokud ne, uzel ukazuje něco, co není pravda: řekněte ne a použijte jiný uzel. Pokud po obnově
chybí mince, o kterých víte, je na stránce Více volba "Prohlédnout dvakrát tolik".

## Vybrání papírové adresy

Více, "Vybrání papírové adresy": napište slova papírové adresy. Aplikace ukáže, co na ní je. Přesuňte vše,
do této peněženky nebo zčásti na novou papírovou adresu: papír, jehož slova byla napsána do telefonu, už není
studený. Odměny za bloky mladší než 100 bloků zatím přesunout nejdou; aplikace řekne, kolik zůstává a na jak
dlouho, a papír je pak třeba uschovat a adresu vybrat ještě jednou. Nikdy do telefonu nepište slova papírové
adresy, na které je víc, než byste v telefonu nosili.

## Zámek a odebrání

Aplikace se po minutě na pozadí zamkne a chce otisk znovu (Více, "Při otevření aplikace chtít otisk prstu").
Zámek v horní liště ji zamkne hned; telefon se pak na otisk zeptá až po klepnutí na Odemknout.
Odebrání peněženky z telefonu zničí uložená slova; mince zůstávají v řetězci a dostanete se k nim slovy
z papíru. Odinstalování aplikace nebo vymazání jejích dat udělá totéž.

## Vlastní uzel

Na počítači s TrueNorth Core (v30.5.4 nebo novější) přidejte do `truenorth.conf`:

    server=1
    blockfilterindex=1
    rpcauth=phone:<vytvořeno skriptem rpcauth.py, stejně jako u Bitcoin Core>
    rpcwhitelist=phone:getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee,testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps
    rpcwhitelistdefault=0

Tento uživatel smí číst řetězec a předávat hotové transakce, nic jiného. Telefon potřebuje https s certifikátem,
kterému důvěřuje: nejjednodušší je Tailscale Serve před portem RPC (uzel je pak dosažitelný jen z vašich
zařízení); poslouží i webový server s certifikátem. Potom vytvořte soubor s nastavením a pošlete ho do telefonu:

    {"format":"polarka-setup/2","node":{"url":"https://vas-uzel.example","user":"phone","password":"..."},"chain":"main","addresses":[]}

V `addresses` může být až dvacet adres, které chcete sledovat bez jejich klíčů, například adresa, na kterou
těžíte. Soubor načtěte na první obrazovce ("Vybrat soubor s nastavením"). Heslo se neukazuje, ukládá se
zašifrované a aplikace nabídne soubor potom smazat.
