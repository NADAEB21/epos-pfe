// Driver pour `flutter drive` — requis par integration_test sur la cible web.
// Ne contient aucune logique : tout le scénario vit dans integration_test/.
import 'package:integration_test/integration_test_driver.dart';

Future<void> main() => integrationDriver();
