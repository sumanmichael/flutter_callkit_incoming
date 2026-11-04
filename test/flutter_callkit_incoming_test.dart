import 'package:flutter/services.dart';
import 'package:flutter_callkit_incoming/flutter_callkit_incoming.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('flutter_callkit_incoming');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(channel, (methodCall) async => '42');
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('setSystemCallLogEnabled sends the choice and returns the result',
      () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (methodCall) async {
      calls.add(methodCall);
      return false;
    });

    expect(await FlutterCallkitIncoming.setSystemCallLogEnabled(false), isFalse);

    expect(calls.single.method, 'setSystemCallLogEnabled');
    expect(calls.single.arguments, {'enabled': false});
  });

  test('isSystemCallLogSupported asks the plugin', () async {
    messenger.setMockMethodCallHandler(channel, (methodCall) async {
      expect(methodCall.method, 'isSystemCallLogSupported');
      return true;
    });

    expect(await FlutterCallkitIncoming.isSystemCallLogSupported(), isTrue);
  });
}
