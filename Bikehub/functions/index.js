const { onRequest } = require("firebase-functions/v2/https");

// Questa funzione HTTP fa da "proxy" verso OpenRouteService
// e legge la chiave ORS dal Secret ORS_API_KEY
exports.orsProxy = onRequest(
  {
    cors: true,                 // abilita le chiamate dal browser :contentReference[oaicite:1]{index=1}
    secrets: ["ORS_API_KEY"],   // rende disponibile il secret alla funzione
  },
  async (req, res) => {
    // Permettiamo solo POST
    if (req.method !== "POST") {
      res.status(405).json({ error: "Metodo non permesso. Usa POST." });
      return;
    }

    const apiKey = process.env.ORS_API_KEY;
    if (!apiKey) {
      res.status(500).json({ error: "Chiave ORS non configurata sul server." });
      return;
    }

    const { mode, profile, coordinates, lengthMeters, seed, avoid } = req.body || {};

    if (!profile || !Array.isArray(coordinates) || coordinates.length < 1) {
      res.status(400).json({ error: "Parametri mancanti (profile o coordinates)." });
      return;
    }

    const url = `https://api.openrouteservice.org/v2/directions/${profile}/geojson`;

    const body = {
      coordinates,
      elevation: "true",
      options: {},
      extra_info: ["steepness", "surface"],
    };

    // Caso giro ad anello
    if (mode === "roundtrip") {
      if (!lengthMeters) {
        res.status(400).json({ error: "lengthMeters mancante per il roundtrip." });
        return;
      }
      body.options.round_trip = {
        length: lengthMeters,
        seed: seed || 1,
      };
    }

    // Evita caratteristiche (autostrade, ecc.)
    if (Array.isArray(avoid) && avoid.length > 0) {
      body.options.avoid_features = avoid;
    }

    try {
      const orsRes = await fetch(url, {
        method: "POST",
        headers: {
          Authorization: apiKey,
          "Content-Type": "application/json; charset=utf-8",
          Accept: "application/geo+json, application/json",
        },
        body: JSON.stringify(body),
      });

      const text = await orsRes.text();

      res
        .status(orsRes.status)
        .set("Content-Type", "application/json; charset=utf-8")
        .send(text);
    } catch (err) {
      console.error("Errore chiamata ORS:", err);
      res.status(500).json({ error: "Errore nel server Bike4city / ORS." });
    }
  }
);
