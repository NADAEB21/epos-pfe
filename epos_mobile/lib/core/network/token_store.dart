// lib/core/network/token_store.dart
// ================================================
// Stockage des tokens avec repli quand le stockage sécurisé est indisponible.
//
// POURQUOI (démo LAN, 2026-07-23) : sur le web, flutter_secure_storage repose sur
// WebCrypto (`crypto.subtle.generateKey`), que les navigateurs n'exposent QUE dans
// un contexte sécurisé (https ou localhost). Servie en `http://<ip-lan>:4300`
// depuis un téléphone, l'app plantait NET au login :
//   « TypeError: Cannot read properties of undefined (reading 'generateKey') ».
// Invisible en dev : localhost EST un contexte sécurisé — le laptop ne pouvait
// pas reproduire le crash du téléphone.
//
// Repli : mémoire process. Les tokens ne survivent pas à un rafraîchissement de
// page (on se reconnecte) — acceptable pour une démo LAN, et infiniment mieux
// qu'un écran mort. Les plateformes sécurisées (Android/iOS/desktop, web https)
// continuent d'utiliser le stockage chiffré comme avant.
//
// L'API imite exactement FlutterSecureStorage (read/write/deleteAll à paramètres
// nommés) pour que les sites d'appel ne changent que de TYPE.
// ================================================

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class TokenStore {
  final FlutterSecureStorage _secure;

  /// Repli mémoire — rempli seulement si le stockage sécurisé a échoué une fois.
  final Map<String, String> _mem = {};
  bool _secureBroken = false;

  TokenStore(this._secure);

  Future<void> write({required String key, required String? value}) async {
    if (!_secureBroken) {
      try {
        await _secure.write(key: key, value: value);
        return;
      } catch (e) {
        _secureBroken = true;
        debugPrint('TokenStore: stockage sécurisé indisponible ($e) — repli mémoire '
            '(contexte non sécurisé ? servir en https ou localhost pour le retrouver).');
      }
    }
    if (value == null) {
      _mem.remove(key);
    } else {
      _mem[key] = value;
    }
  }

  Future<String?> read({required String key}) async {
    if (!_secureBroken) {
      try {
        return await _secure.read(key: key);
      } catch (_) {
        _secureBroken = true;
      }
    }
    return _mem[key];
  }

  Future<void> deleteAll() async {
    if (!_secureBroken) {
      try {
        await _secure.deleteAll();
        return;
      } catch (_) {
        _secureBroken = true;
      }
    }
    _mem.clear();
  }
}
