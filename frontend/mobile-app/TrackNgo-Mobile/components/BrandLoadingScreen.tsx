import React, { useEffect, useRef } from "react";
import { Animated, Easing, StyleSheet, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { LocalizedText as Text } from "../utils/i18n";

/*
 * The TrackNGo launch screen: logo, wordmark and a filling progress bar.
 *
 * This is deliberately presentational. The welcome route used to own both the
 * animation and the "now go to login" navigation, which meant the screen could
 * not be reused anywhere else - rendering it while the saved session was being
 * restored would have thrown the user out to login mid-restore. Navigation now
 * lives with the caller via onFinish: the welcome route passes one, and the
 * loading states do not, so they show the same screen and simply hold.
 */
export default function BrandLoadingScreen({ onFinish }: { onFinish?: () => void }) {
  const logoScale = useRef(new Animated.Value(0.7)).current;
  const logoOpacity = useRef(new Animated.Value(0)).current;
  const textOpacity = useRef(new Animated.Value(0)).current;
  const barWidth = useRef(new Animated.Value(0)).current;

  // Held in a ref so a re-render with a new callback identity cannot restart
  // the animation, which would make the bar jump back to zero mid-fill.
  const finishRef = useRef(onFinish);
  finishRef.current = onFinish;

  useEffect(() => {
    const animation = Animated.sequence([
      Animated.parallel([
        Animated.timing(logoScale, {
          toValue: 1,
          duration: 600,
          easing: Easing.out(Easing.back(1.5)),
          useNativeDriver: true,
        }),
        Animated.timing(logoOpacity, {
          toValue: 1,
          duration: 500,
          useNativeDriver: true,
        }),
      ]),
      Animated.timing(textOpacity, {
        toValue: 1,
        duration: 400,
        useNativeDriver: true,
      }),
      Animated.timing(barWidth, {
        toValue: 1,
        duration: 1800,
        easing: Easing.inOut(Easing.ease),
        useNativeDriver: false,
      }),
    ]);

    animation.start(({ finished }) => {
      // A session that restores quickly unmounts this mid-animation; running the
      // callback then would navigate on top of the screen that already replaced us.
      if (finished) finishRef.current?.();
    });

    return () => animation.stop();
  }, [barWidth, logoOpacity, logoScale, textOpacity]);

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <Animated.View
          style={[
            styles.logoCircle,
            { opacity: logoOpacity, transform: [{ scale: logoScale }] },
          ]}
        >
          <View style={styles.logoInner}>
            <View style={styles.iconBox}>
              <Ionicons name="bus" size={40} color="#FFFFFF" />
              <View style={styles.pinBadge}>
                <Ionicons name="location" size={16} color="#FFFFFF" />
              </View>
            </View>
          </View>
        </Animated.View>

        <Animated.View style={{ opacity: textOpacity, alignItems: "center" }}>
          <Text style={styles.brand}>TrackNGo</Text>
          <Text style={styles.tagline}>Your Journey, Simplified</Text>
        </Animated.View>
      </View>

      <View style={styles.barContainer}>
        <View style={styles.barTrack}>
          <Animated.View
            style={[
              styles.barFill,
              {
                width: barWidth.interpolate({
                  inputRange: [0, 1],
                  outputRange: ["0%", "100%"],
                }),
              },
            ]}
          />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#F6F7F9",
    justifyContent: "space-between",
    alignItems: "center",
    paddingBottom: 60,
  },
  content: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  logoCircle: {
    width: 180,
    height: 180,
    borderRadius: 90,
    backgroundColor: "#E8EFFF",
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 28,
  },
  logoInner: {
    width: 110,
    height: 110,
    borderRadius: 24,
    backgroundColor: "#FFFFFF",
    alignItems: "center",
    justifyContent: "center",
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 4,
  },
  iconBox: {
    width: 80,
    height: 80,
    borderRadius: 16,
    backgroundColor: "#2F6BFF",
    alignItems: "center",
    justifyContent: "center",
  },
  pinBadge: {
    position: "absolute",
    bottom: 8,
    right: 8,
  },
  brand: {
    fontSize: 32,
    fontWeight: "800",
    color: "#1F2937",
    letterSpacing: -0.5,
  },
  tagline: {
    fontSize: 16,
    color: "#94A3B8",
    marginTop: 6,
    fontWeight: "500",
  },
  barContainer: {
    width: 200,
    alignItems: "center",
  },
  barTrack: {
    width: "100%",
    height: 5,
    borderRadius: 3,
    backgroundColor: "#E2E8F0",
    overflow: "hidden",
  },
  barFill: {
    height: "100%",
    borderRadius: 3,
    backgroundColor: "#2F6BFF",
  },
});
