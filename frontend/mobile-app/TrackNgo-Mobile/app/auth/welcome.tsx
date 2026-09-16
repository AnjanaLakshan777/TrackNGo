import React from "react";
import { useRouter } from "expo-router";
import BrandLoadingScreen from "../../components/BrandLoadingScreen";

/*
 * The launch route. The screen itself lives in BrandLoadingScreen so the same
 * visual can be shown while a saved session is being restored; this route adds
 * the one thing unique to it, which is where to go once the animation ends.
 */
export default function WelcomeScreen() {
  const router = useRouter();
  return <BrandLoadingScreen onFinish={() => router.replace("/auth/login")} />;
}
