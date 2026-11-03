import 'package:build_tool/src/go_builder.dart';
import 'package:build_tool/src/options.dart';
import 'package:build_tool/src/target.dart';
import 'package:test/test.dart';

class RecordingGoBuilder extends GoBuilder {
  RecordingGoBuilder()
      : super(
          rootDir: '.',
          config: const BuildConfig(
            tags: '',
            goLdflags: '',
            coreDir: 'core',
            coreName: 'core',
            libName: 'libcore',
            outputDir: 'output',
            helperDir: 'helper',
            helperName: 'helper',
            distDir: 'dist',
          ),
        );

  final events = <String>[];

  @override
  Future<String> build(Target target) async {
    events.add('start:${target.goarch}');
    await Future<void>.delayed(const Duration(milliseconds: 1));
    events.add('end:${target.goarch}');
    return target.goarch;
  }
}

void main() {
  test('buildAll builds targets sequentially', () async {
    final builder = RecordingGoBuilder();

    final outputs = await builder.buildAll([
      Target.androidArm,
      Target.androidArm64,
      Target.androidAmd64,
    ]);

    expect(outputs, ['arm', 'arm64', 'amd64']);
    expect(builder.events, [
      'start:arm',
      'end:arm',
      'start:arm64',
      'end:arm64',
      'start:amd64',
      'end:amd64',
    ]);
  });
}
