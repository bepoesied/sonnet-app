{ pkgs
, ...
}:
{
  packages = [
    pkgs.jdk21
    pkgs.android-tools
    pkgs.aapt
    pkgs.git
  ];

  env = {
    JAVA_HOME = "${pkgs.jdk21.home}";
    AAPT2_PATH = "${pkgs.aapt}/bin/aapt2";
  };
}
