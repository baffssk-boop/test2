# OEE-räknare

En Android-app som räknar OEE (tillgänglighet × prestanda × kvalitet) under ett skift och registrerar stopp automatiskt med en rörelsesensor.

## Funktioner

- **OEE i realtid** med tillgänglighet, prestanda och kvalitet
- **Räknare** för bra och kasserade detaljer
- **Automatiska stopp**: ingen rörelse under inställd tid ger ett stopp, och när rörelsen kommer tillbaka får du välja orsak
- **Sensorer**: en Bluetooth LE-sensor (varje avisering från enheten räknas som rörelse) eller telefonens egen accelerometer
- **Stopporsaker och kategorier** som du lägger upp själv. Kategorier markerade som *planerade* (t.ex. rast) räknas bort från planerad produktionstid
- **Stopptid per kategori** och export som CSV via dela-menyn

## Installera

Varje gång koden ändras bygger GitHub appen automatiskt. Den färdiga filen hittar du under **Releases** i repot: ladda ner `OEE-raknare.apk` och öppna den på telefonen.

## Hur OEE räknas

- Planerad produktionstid = skifttid − planerade stopp
- Gångtid = planerad produktionstid − oplanerade stopp
- Tillgänglighet = gångtid / planerad produktionstid
- Prestanda = idealcykeltid × antal producerade / gångtid
- Kvalitet = bra / (bra + kass)

# Isabellas Kattäventyr

En läs- och ordapp för barn, med katten Blixten som följeslagare. Varje uppdrag har ordbilder, meningar att förstå, en rörelsepaus och ett rituppdrag, och ger en squishy att samla. Alla ord kan läsas upp med telefonens talsyntes.

Installera: ladda ner `Kattaventyr.apk` under **Releases** och öppna den på telefonen. Sidan ligger i mappen `katt/`.
