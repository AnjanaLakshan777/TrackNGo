import { Redirect } from "expo-router";
import { useSession } from "../store/sessionStore";
import BrandLoadingScreen from "../components/BrandLoadingScreen";

export default function Index() {
  const { loading } = useSession();

  // Shown instead of a bare spinner, so the first frame is the launch screen
  // rather than an anonymous loading indicator.
  if (loading) {
    return <BrandLoadingScreen />;
  }

  return <Redirect href="/auth/welcome" />;
}
