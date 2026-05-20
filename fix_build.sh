sed -i '/org.jupnp.android/d' app/build.gradle
cat << 'INNER_EOF' >> app/build.gradle
// Remove the broken line
INNER_EOF
