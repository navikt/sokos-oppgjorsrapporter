const http = require("axios");

const fetchToken = async (path) => {
  const respons = await http.get(`https://pengeflyt-token.intern.dev.nav.no/${path}`, {timeout: 5000});

  if (respons.status !== 200) {
    throw new Error(`Uventet respons ved henting av token fra ${path}: ${respons.status}.  Sjekk at Naisdevice er grønn.`);
  }

  return respons.data;
}

const initBearerAuth = async (path, req) => req.setHeader("Authorization", "Bearer " + await fetchToken(path));

module.exports = {
  fetchToken,
  initBearerAuth,
};
